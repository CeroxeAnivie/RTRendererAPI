"""Run with the official Blender binary in background mode, with GPU-only Cycles.

Reads the exact exported geometry and material parameters. Cross-renderer BSDF,
volume-boundary interpolation, per-volume scattering limits and priority behavior
are explicitly not claimed to be pixel-equivalent.
"""
import argparse
import json
import math
from pathlib import Path
import statistics
import subprocess
import sys
import time

import bpy
from mathutils import Matrix, Vector


def nums(value):
    return [float(v) for v in str(value).split()]


def gpu():
    return subprocess.check_output(['nvidia-smi', '--query-gpu=timestamp,name,utilization.gpu,power.draw,clocks.sm', '--format=csv,noheader'], text=True).strip()


def attach(tree, socket, value):
    if isinstance(value, bpy.types.NodeSocket):
        tree.links.new(value, socket)
    elif socket.type == 'RGBA':
        components = list(value) if isinstance(value, (tuple, list)) else [value]*3
        socket.default_value = tuple((components*3)[:3])+ (1,)
    elif socket.type == 'VECTOR':
        socket.default_value = tuple(value) if isinstance(value, (tuple, list)) else (value,)*3
    else:
        socket.default_value = float(value[0] if isinstance(value, (tuple, list)) else value)


class Importer:
    def __init__(self, fixture):
        self.f = fixture
        self.images = {}
        for name, pixels in fixture['images'].items():
            height, width = len(pixels), len(pixels[0])
            image = bpy.data.images.new(name, width=width, height=height, alpha=True, float_buffer=True)
            image.colorspace_settings.name = 'Non-Color'
            rgba = []
            for row in pixels:
                for p in row:
                    rgba.extend((p+[1,1,1,1])[:4])
            image.pixels.foreach_set(rgba)
            image.update()
            self.images[name] = image

    def texture(self, tree, value):
        try:
            v = nums(value)
            return v[0] if len(v) == 1 else v
        except ValueError:
            pass
        t = self.f['textures'][value]
        kind = t['type']
        node = tree.nodes.new
        if kind == 'imagemap':
            n = node('ShaderNodeTexImage')
            n.image = self.images[t['file']]
            n.interpolation = 'Linear'
            n.extension = 'REPEAT'
            return n.outputs['Alpha' if value.endswith('alpha') else 'Color']
        if kind in ('hitpointcolor', 'hitpointalpha'):
            n = node('ShaderNodeVertexColor')
            n.layer_name = 'Color'
            return n.outputs['Alpha' if kind == 'hitpointalpha' else 'Color']
        if kind in ('scale', 'subtract', 'greaterthan'):
            a, b = self.texture(tree, t['texture1']), self.texture(tree, t['texture2'])
            colored = kind == 'scale' and any(isinstance(v, (tuple, list)) or (isinstance(v,bpy.types.NodeSocket) and v.type == 'RGBA') for v in (a,b))
            if colored:
                n = node('ShaderNodeMixRGB')
                n.blend_type = 'MULTIPLY'
                n.inputs[0].default_value = 1
                attach(tree,n.inputs[1],a);attach(tree,n.inputs[2],b)
            else:
                n = node('ShaderNodeMath')
                n.operation = {'scale':'MULTIPLY','subtract':'SUBTRACT','greaterthan':'GREATER_THAN'}[kind]
                attach(tree,n.inputs[0],a);attach(tree,n.inputs[1],b)
            return n.outputs[0]
        if kind == 'splitfloat3':
            n = node('ShaderNodeSeparateColor')
            attach(tree,n.inputs[0],self.texture(tree,t['texture']))
            return n.outputs[int(t['channel'])]
        if kind == 'normalmap':
            n = node('ShaderNodeNormalMap')
            attach(tree,n.inputs['Color'],self.texture(tree,t['texture']))
            n.inputs['Strength'].default_value = float(t.get('scale','1'))
            return n.outputs['Normal']
        if kind == 'densitygrid':
            data = nums(t['data']); nx,ny,nz = (int(t[k]) for k in ('nx','ny','nz'))
            # This fixture is exactly an x-gradient, identical on every y/z row.
            assert all(data[i:i+nx] == data[:nx] for i in range(0,len(data),nx))
            coordinates=node('ShaderNodeTexCoord');split=node('ShaderNodeSeparateXYZ')
            tree.links.new(coordinates.outputs['Generated'],split.inputs[0])
            ramp=node('ShaderNodeValToRGB');ramp.color_ramp.interpolation='LINEAR'
            for i in range(nx):
                element = ramp.color_ramp.elements[i] if i < 2 else ramp.color_ramp.elements.new((i+.5)/nx)
                element.position=(i+.5)/nx;element.color=(data[i],data[i],data[i],1)
            tree.links.new(split.outputs['X'],ramp.inputs[0])
            return ramp.outputs['Color']
        raise ValueError('Unsupported comparison texture '+value+' type='+kind)

    def surface(self,tree,name):
        m=self.f['materials'][name];kind=m['type'];new=tree.nodes.new
        if kind == 'null':
            n=new('ShaderNodeBsdfTransparent');result=n.outputs[0]
        elif kind in ('disney','matte'):
            n=new('ShaderNodeBsdfPrincipled') if kind == 'disney' else new('ShaderNodeBsdfDiffuse')
            attach(tree,n.inputs['Base Color' if kind == 'disney' else 'Color'],self.texture(tree,m.get('basecolor',m.get('kd','0.5 0.6 0.7'))))
            attach(tree,n.inputs['Roughness'],self.texture(tree,m.get('roughness','0')))
            if kind == 'disney':
                attach(tree,n.inputs['Metallic'],self.texture(tree,m.get('metallic','0')))
                attach(tree,n.inputs['Specular IOR Level'],self.texture(tree,m.get('specular','0')))
            if 'bumptex' in m:
                attach(tree,n.inputs['Normal'],self.texture(tree,m['bumptex']))
            result=n.outputs[0]
        elif kind in ('glass','roughglass'):
            n=new('ShaderNodeBsdfGlass');n.distribution='BECKMANN' if kind == 'glass' else 'GGX'
            n.inputs['Roughness'].default_value = float(m.get('uroughness','0'))
            n.inputs['IOR'].default_value=float(m.get('interiorior','1.5'))
            result=n.outputs[0]
        elif kind == 'mix':
            n=new('ShaderNodeMixShader');attach(tree,n.inputs[0],self.texture(tree,m['amount']))
            tree.links.new(self.surface(tree,m['material1']),n.inputs[1]);tree.links.new(self.surface(tree,m['material2']),n.inputs[2]);result=n.outputs[0]
        else:
            raise ValueError('Unsupported surface '+kind)
        if 'transparency.front' in m:
            n=new('ShaderNodeMixShader');attach(tree,n.inputs[0],self.texture(tree,m['transparency.front']))
            transparent=new('ShaderNodeBsdfTransparent');tree.links.new(result,n.inputs[1]);tree.links.new(transparent.outputs[0],n.inputs[2]);result=n.outputs[0]
        if 'emission' in m:
            emitter=new('ShaderNodeEmission');attach(tree,emitter.inputs['Color'],self.texture(tree,m['emission']))
            add=new('ShaderNodeAddShader');tree.links.new(result,add.inputs[0]);tree.links.new(emitter.outputs[0],add.inputs[1]);result=add.outputs[0]
        return result

    def material(self,name):
        material=bpy.data.materials.new(name);material.use_nodes=True
        tree=material.node_tree;tree.nodes.clear();output=tree.nodes.new('ShaderNodeOutputMaterial')
        tree.links.new(self.surface(tree,name),output.inputs['Surface'])
        m=self.f['materials'][name]
        if 'volume.interior' in m:
            volume=self.f['volumes'][m['volume.interior']];closures=[]
            for key,kind in [('absorption','ShaderNodeVolumeAbsorption'),('scattering','ShaderNodeVolumeScatter'),('emission','ShaderNodeEmission')]:
                value=volume.get(key,'0 0 0')
                try:
                    if max(nums(value)) == 0:continue
                except ValueError:
                    pass
                n=tree.nodes.new(kind);color=self.texture(tree,value)
                if key == 'absorption':
                    inverse=tree.nodes.new('ShaderNodeInvert');attach(tree,inverse.inputs['Color'],color);color=inverse.outputs['Color']
                attach(tree,n.inputs['Color'],color)
                if 'Density' in n.inputs:n.inputs['Density'].default_value=1
                if key == 'scattering':n.inputs['Anisotropy'].default_value=float(volume.get('asymmetry','0'))
                closures.append(n.outputs[0])
            if closures:
                combined=closures[0]
                for extra in closures[1:]:
                    add=tree.nodes.new('ShaderNodeAddShader');tree.links.new(combined,add.inputs[0]);tree.links.new(extra,add.inputs[1]);combined=add.outputs[0]
                tree.links.new(combined,output.inputs['Volume'])
        return material

    def geometry(self,name,obj,material):
        src=self.f['meshes'][obj['ply']];vertices=src['vertices'];mesh=bpy.data.meshes.new(name)
        mesh.from_pydata([(p['x'],p['y'],p['z']) for p in vertices],[],src['triangles']);mesh.update()
        colors=mesh.color_attributes.new(name='Color',type='FLOAT_COLOR',domain='POINT')
        colors.data.foreach_set('color',[v for p in vertices for v in (p.get('red',1),p.get('green',1),p.get('blue',1),p.get('alpha',1))])
        uv=mesh.uv_layers.new(name='UVMap')
        for loop in mesh.loops:uv.data[loop.index].uv=(vertices[loop.vertex_index].get('s',0),vertices[loop.vertex_index].get('t',0))
        mesh.materials.append(material);o=bpy.data.objects.new(name,mesh);bpy.context.collection.objects.link(o)
        v=nums(obj['transformation']);o.matrix_world=Matrix(tuple(tuple(v[c*4+r] for c in range(4)) for r in range(4)))
        return o


def setup(fixture,case):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.device='GPU'
    prefs=bpy.context.preferences.addons['cycles'].preferences;prefs.compute_device_type='OPTIX';prefs.get_devices()
    for d in prefs.devices:d.use=d.type=='OPTIX' and '5080' in d.name
    devices=[d.name for d in prefs.devices if d.use]
    assert devices, 'No RTX 5080 OptiX device'
    scene.render.resolution_x=1920;scene.render.resolution_y=1080;scene.render.resolution_percentage=100
    scene.render.use_persistent_data=True;scene.cycles.use_denoising=False;scene.cycles.use_adaptive_sampling=False
    scene.cycles.max_bounces=5;scene.cycles.diffuse_bounces=3;scene.cycles.glossy_bounces=3;scene.cycles.transmission_bounces=3
    scene.cycles.volume_bounces=5;scene.cycles.transparent_max_bounces=64
    scene.cycles.pixel_filter_type='BOX';scene.cycles.filter_width=1
    scene.view_settings.view_transform='Standard';scene.view_settings.look='None';scene.view_settings.exposure=0
    camera=bpy.data.cameras.new('Camera');camera.sensor_fit='VERTICAL';camera.sensor_height=24;camera.lens=24/(2*math.tan(math.radians(15)));camera.clip_start=.001
    cam=bpy.data.objects.new('Camera',camera);bpy.context.collection.objects.link(cam);cam.location=(0,0,7);scene.camera=cam
    world=bpy.data.worlds.new('World');world.use_nodes=True;scene.world=world
    world.node_tree.nodes['Background'].inputs['Color'].default_value=(.12,.16,.22,1);world.node_tree.nodes['Background'].inputs['Strength'].default_value=1
    importer=Importer(fixture)
    if case == 'minimal':
        material=bpy.data.materials.new('Diffuse');material.use_nodes=True;tree=material.node_tree;tree.nodes.clear()
        node=tree.nodes.new('ShaderNodeBsdfDiffuse');node.inputs['Color'].default_value=(.5,.6,.7,1);out=tree.nodes.new('ShaderNodeOutputMaterial');tree.links.new(node.outputs[0],out.inputs['Surface'])
        importer.geometry('Cube',{'ply':'mesh-00000.ply','transformation':'0.28 0 0 0 0 0.28 0 0 0 0 0.28 0 0 0 0 1'},material)
    else:
        for name,obj in fixture['objects'].items():importer.geometry(name,obj,importer.material(obj['material']))
        for name,l in fixture['lights'].items():
            kind=l['type']
            if kind=='constantinfinite':continue
            if kind not in ('point','spot','sharpdistant'):raise ValueError('Unexpected light '+kind)
            gain=nums(l.get('gain','1 1 1'));color=nums(l.get('color','1 1 1'))
            if max(gain)==0:continue
            data=bpy.data.lights.new(name,{'point':'POINT','spot':'SPOT','sharpdistant':'SUN'}[kind]);data.color=tuple(color)
            data.energy=gain[0]*(4*math.pi if kind in ('point','spot') else 1);data.shadow_soft_size=0 if kind in ('point','spot') else data.shadow_soft_size
            obj=bpy.data.objects.new(name,data);bpy.context.collection.objects.link(obj)
            if kind in ('point','spot'):obj.location=nums(l['position'])
            if kind=='spot':
                direction=Vector(nums(l['target']))-obj.location;obj.rotation_euler=direction.to_track_quat('-Z','Y').to_euler()
                data.spot_size=math.radians(2*float(l['coneangle']));data.spot_blend=float(l['conedeltaangle'])/float(l['coneangle'])
            if kind=='sharpdistant':
                obj.rotation_euler=Vector(nums(l['direction'])).to_track_quat('-Z','Y').to_euler();data.angle=0
    return scene,devices


def benchmark(fixture,case,output):
    initial=gpu();print('CyclesControl',case,'initialGpu',initial,flush=True)
    scene,devices=setup(fixture,case)
    scene.cycles.samples=1;bpy.ops.render.render()
    frames=[]
    for i in range(4):
        scene.cycles.seed=i+1;start=time.perf_counter();bpy.ops.render.render();frames.append((time.perf_counter()-start)*1000)
    batches=[]
    for samples in (16,64,16,64):
        scene.cycles.samples=samples;scene.cycles.seed=samples
        start=time.perf_counter();bpy.ops.render.render();elapsed=(time.perf_counter()-start)*1000
        batches.append({'spp':samples,'wallMs':elapsed,'sppPerSecond':samples*1000/elapsed})
        print('CyclesControl',case,'batch',samples,'ms',elapsed,flush=True)
    low=statistics.mean(x['wallMs'] for x in batches if x['spp']==16);high=statistics.mean(x['wallMs'] for x in batches if x['spp']==64)
    result={'renderer':'Blender Cycles','version':bpy.app.version_string,'backend':'OPTIX','devices':devices,'scene':case,
            'width':1920,'height':1080,'pathDepth':5,'denoiser':False,'adaptiveSampling':False,'filter':'BOX','initialGpu':initial,
            'independent1SppFrameMs':frames,'independent1SppFps':len(frames)*1000/sum(frames),'batches':batches,
            'batch64SppPerSecond':64*1000/high,'incrementalSppPerSecond':48000/(high-low) if high>low else None,
            'triangleCount':12 if case=='minimal' else fixture['triangleCount'],
            'semanticDifferences':[] if case=='minimal' else [
                'Native Cycles BSDFs, sampling and light units rather than LuxCore exact BSDF implementation.',
                'Cycles overlapping volumes rather than LuxCore explicit volume priority.',
                'Global multiple scattering rather than LuxCore per-volume single/multiple switches.',
                'Same 4x4x4 x-gradient density expressed using native Generated-coordinate ColorRamp nodes; edge interpolation differs.',
                'No claim of pixel-identical cross-engine full API coverage. Geometry, transforms, coefficients, alpha/texture/normal/MR nodes, emission and light types are retained.']}
    output.mkdir(parents=True,exist_ok=True);scene.render.image_settings.file_format='PNG'
    bpy.data.images['Render Result'].save_render(str(output/(case+'.png')),scene=scene)
    (output/(case+'.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
    print('CyclesControl RESULT',json.dumps(result),flush=True)


args=sys.argv[sys.argv.index('--')+1:]
parser=argparse.ArgumentParser();parser.add_argument('--fixture',type=Path,default=Path('build/reports/renderer-comparison/fixture.json'));parser.add_argument('--output',type=Path,default=Path('build/reports/renderer-comparison/cycles'));parser.add_argument('--case',choices=['minimal','mixed','both'],default='both')
options=parser.parse_args(args);fixture=json.loads(options.fixture.read_text(encoding='utf-8'))
for case in (['minimal','mixed'] if options.case=='both' else [options.case]):benchmark(fixture,case,options.output)
