"""Native EEVEE + unmodified Flow: full surface/depth render -> GPU handoff -> media.

Run inside official Blender, with a real OpenGL context. No desktop automation.
No per-frame CPU pixel transfers, cached background images, or custom integrators.
"""
import argparse
import ctypes as C
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time
import traceback

import bpy
import gpu

ROOT=Path(__file__).resolve().parents[1]
W,H=1920,1080
P=C.c_void_p; U=C.c_uint; I=C.c_int; Q=C.c_uint64


class GL:
    def __init__(self):
        self.lib=C.WinDLL('opengl32.dll');self.lib.wglGetProcAddress.argtypes=[C.c_char_p];self.lib.wglGetProcAddress.restype=P
        self.functions={}
    def fn(self,name,restype,*args):
        if name not in self.functions:
            address=self.lib.wglGetProcAddress(name.encode())
            if not address:
                address=C.cast(getattr(self.lib,name),P).value
            if not address or address in (1,2,3,0xffffffffffffffff):raise RuntimeError('OpenGL entry unavailable: '+name)
            self.functions[name]=C.WINFUNCTYPE(restype,*args)(address)
        return self.functions[name]
    def get(self,key):
        value=I();self.fn('glGetIntegerv',None,U,C.POINTER(I))(key,C.byref(value));return value.value
    def check(self,label):
        error=self.fn('glGetError',U)()
        if error:raise RuntimeError(label+': OpenGL error '+hex(error))
    def generate(self,name):
        value=U();self.fn(name,None,I,C.POINTER(U))(1,C.byref(value));return value.value


def run():
    parser=argparse.ArgumentParser();parser.add_argument('--fixture',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--device',type=int,required=True)
    parser.add_argument('--seconds',type=float,default=3);a=parser.parse_args(sys.argv[sys.argv.index('--')+1:])
    out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    # Reuse the existing exact geometry/material/light adapter; the rendering is all upstream.
    namespace={'__name__':'flow_surface_importer'}
    exec((ROOT/'tools/compare_cycles.py').read_text(encoding='utf-8').split('\nargs=sys.argv')[0],namespace)
    fixture=json.loads(a.fixture.read_text(encoding='utf-8'));scene,_=namespace['setup'](fixture,'mixed')
    scene.render.engine='BLENDER_EEVEE_NEXT';scene.camera.data.clip_end=100
    for material in bpy.data.materials:
        if material.use_nodes:
            for node in material.node_tree.nodes:
                if node.type=='OUTPUT_MATERIAL':
                    for link in list(node.inputs['Volume'].links):material.node_tree.links.remove(link)
    for key in ['taa_samples','taa_render_samples']:
        if hasattr(scene.eevee,key):setattr(scene.eevee,key,1)
    renderer=gpu.platform.renderer_get();print('FLOW MIXED OpenGL device:',renderer,flush=True)
    assert '5080' in renderer,'OpenGL surface renderer selected wrong GPU: '+renderer
    window=bpy.context.window_manager.windows[0];window.scene=scene
    area=next(x for x in window.screen.areas if x.type=='VIEW_3D')
    space=area.spaces.active;region=next(x for x in area.regions if x.type=='WINDOW')
    space.overlay.show_overlays=False;space.shading.type='RENDERED'
    space.shading.use_scene_world_render=True;space.shading.use_scene_lights_render=True
    bpy.context.view_layer.update();depsgraph=bpy.context.evaluated_depsgraph_get()
    view=scene.camera.matrix_world.inverted()
    projection=scene.camera.calc_matrix_camera(depsgraph,x=W,y=H,scale_x=1,scale_y=1)
    offscreen=gpu.types.GPUOffScreen(W,H,format='RGBA16F')
    def surface():
        with bpy.context.temp_override(window=window,area=area,region=region):
            offscreen.draw_view3d(scene,scene.view_layers[0],space,region,view,projection,do_color_management=False)
    surface()
    gl=GL()
    with offscreen.bind():
        fb=gl.get(0x8CA6)
        kind=I();obj=I()
        query=gl.fn('glGetFramebufferAttachmentParameteriv',None,U,U,U,C.POINTER(I))
        query(0x8D40,0x8D00,0x8CD0,C.byref(kind));query(0x8D40,0x8D00,0x8CD1,C.byref(obj))
        fmt=I()
        if kind.value==0x1702:
            gl.fn('glBindTexture',None,U,U)(0x0DE1,obj.value)
            gl.fn('glGetTexLevelParameteriv',None,U,I,U,C.POINTER(I))(0x0DE1,0,0x1003,C.byref(fmt))
        elif kind.value==0x8D41:
            gl.fn('glBindRenderbuffer',None,U,U)(0x8D41,obj.value)
            gl.fn('glGetRenderbufferParameteriv',None,U,U,C.POINTER(I))(0x8D41,0x8D44,C.byref(fmt))
        else:raise RuntimeError('Missing native surface depth attachment')
        print('Native EEVEE depth attachment:',hex(fmt.value),flush=True)
        flip=gl.generate('glGenFramebuffers');gl.fn('glBindFramebuffer',None,U,U)(0x8D40,flip)
        renderbuffers=[]
        for internal,attachment in [(0x881A,0x8CE0),(fmt.value,0x821A if fmt.value in (0x88F0,0x8CAD) else 0x8D00)]:
            rb=gl.generate('glGenRenderbuffers');renderbuffers.append(rb)
            gl.fn('glBindRenderbuffer',None,U,U)(0x8D41,rb)
            gl.fn('glRenderbufferStorage',None,U,U,I,I)(0x8D41,internal,W,H)
            gl.fn('glFramebufferRenderbuffer',None,U,U,U,U)(0x8D40,attachment,0x8D41,rb)
        assert gl.fn('glCheckFramebufferStatus',U,U)(0x8D40)==0x8CD5,'Flip framebuffer incomplete'
        gl.fn('glBindFramebuffer',None,U,U)(0x8D40,fb);gl.check('Surface framebuffer setup')
    binary=ROOT/'build/flow-sdk/flow/_build/windows-x86_64/release'
    dll_directory=os.add_dll_directory(str(binary));os.environ['PATH']=str(binary)+os.pathsep+os.environ['PATH']
    lib=C.CDLL(str(binary/'flow_mixed_bridge.dll'))
    lib.flowMixedCreate.argtypes=[C.c_char_p,C.c_float,U,C.POINTER(C.c_ubyte)];lib.flowMixedCreate.restype=P
    lib.flowMixedError.restype=C.c_char_p
    for name in ['flowMixedHandle','flowMixedAllocationSize']:
        getattr(lib,name).argtypes=[P,U];getattr(lib,name).restype=Q
    lib.flowMixedFrame.argtypes=[P,I];lib.flowMixedFrame.restype=I
    lib.flowMixedCapture.argtypes=[P,C.c_char_p];lib.flowMixedCapture.restype=I
    lib.flowMixedDestroy.argtypes=[P]
    uuid=(C.c_ubyte*16)();gl.fn('glGetUnsignedBytevEXT',None,U,C.POINTER(C.c_ubyte))(0x9597,uuid)
    gl.check('Surface GPU UUID query');print('Surface GPU UUID:',bytes(uuid).hex(),flush=True)
    host=lib.flowMixedCreate(str(out/'fixture.bin').encode(),.035,a.device,uuid)
    assert host,lib.flowMixedError().decode()
    memories=[];buffers=[]
    for i,size in enumerate([W*H*8,W*H*4]):
        mem=gl.generate('glCreateMemoryObjectsEXT');memories.append(mem)
        gl.fn('glImportMemoryWin32HandleEXT',None,U,Q,U,P)(mem,lib.flowMixedAllocationSize(host,i),0x9587,P(lib.flowMixedHandle(host,i)))
        buf=gl.generate('glGenBuffers');buffers.append(buf)
        gl.fn('glBindBuffer',None,U,U)(0x88EB,buf)
        gl.fn('glBufferStorageMemEXT',None,U,C.c_ssize_t,U,Q)(0x88EB,size,mem,0)
        gl.check('Vulkan/OpenGL shared GPU buffer import')
    gl.fn('glBindBuffer',None,U,U)(0x88EB,0)
    def frame(refresh):
        start=time.perf_counter();surface()
        # Cross-API handoff is ordered explicitly. Copies remain on the GPU.
        with offscreen.bind():
            original=gl.get(0x8CA6)
            gl.fn('glBindFramebuffer',None,U,U)(0x8CA9,flip)
            gl.fn('glBlitFramebuffer',None,I,I,I,I,I,I,I,I,U,U)(0,0,W,H,0,H,W,0,0x4000|0x0100,0x2600)
            gl.fn('glBindFramebuffer',None,U,U)(0x8CA8,flip)
            gl.fn('glReadBuffer',None,U)(0x8CE0)
            for i,(format_,type_) in enumerate([(0x1908,0x140B),(0x1902,0x1406)]):
                gl.fn('glBindBuffer',None,U,U)(0x88EB,buffers[i])
                gl.fn('glReadPixels',None,I,I,I,I,U,U,P)(0,0,W,H,format_,type_,None)
            gl.fn('glBindBuffer',None,U,U)(0x88EB,0)
            gl.fn('glBindFramebuffer',None,U,U)(0x8D40,original)
        gl.fn('glFinish',None)();gl.check('Native surface draw and shared GPU copy')
        middle=time.perf_counter()
        assert lib.flowMixedFrame(host,int(refresh))==0,lib.flowMixedError().decode()
        end=time.perf_counter()
        return [(end-start)*1000,(middle-start)*1000,(end-middle)*1000]
    for _ in range(8):frame(True)
    rows=[]
    for refresh in [False,True]:
        samples=[]
        start=time.perf_counter()
        while len(samples)<60 or time.perf_counter()-start<a.seconds:samples.append(frame(refresh))
        total=sum(s[0] for s in samples)
        row={'renderer':'Native Blender EEVEE + NVIDIA Flow','width':W,'height':H,'fg':False,
             'mode':'per-frame density/shadow refresh' if refresh else 'static density/shadow cache',
             'fps':len(samples)*1000/total,'meanMs':total/len(samples),
             'surfaceAndGPUHandoffMs':statistics.mean(s[1] for s in samples),
             'flowIncludingInputCopyMs':statistics.mean(s[2] for s in samples),'frames':len(samples),
             'frameMs':samples,'surfaceDevice':renderer,'gpuUUID':bytes(uuid).hex(),'fixtureSha256':hashlib.sha256(a.fixture.read_bytes()).hexdigest(),
             'triangles':fixture['triangleCount'],'objects':len(fixture['objects']),
             'metric':'Fresh native EEVEE surface/depth + GPU shared-memory handoff + native Flow volume composite + completion fence; no presentation',
             'semanticDifferences':[
                 'Game raster surface shading (EEVEE) plus Flow ray marching, not path-traced BSDF/volume equivalence.',
                 'Original geometry, surface materials, textures, lights and 32 bounded volume inputs retained.',
                 'Flow scalar extinction/colormap, native directional volume shadows; no HG, volume priority, refractive coupling or camera-medium state.',
                 'Synchronous GL/Vulkan GPU handoff included; no per-frame CPU pixel readback or cached surface image.',
                 'No frame generation. 1 SPP is not a Flow ray-marching parameter.']}
        rows.append(row);print('FLOW_MIXED RESULT',json.dumps({k:v for k,v in row.items() if k!='frameMs'}),flush=True)
    assert lib.flowMixedCapture(host,str(out/'mixed.rgba16f').encode())==0,lib.flowMixedError().decode()
    (out/'result.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
    gl.fn('glFinish',None)()
    for buf in buffers:
        v=U(buf);gl.fn('glDeleteBuffers',None,I,C.POINTER(U))(1,C.byref(v))
    for mem in memories:
        v=U(mem);gl.fn('glDeleteMemoryObjectsEXT',None,I,C.POINTER(U))(1,C.byref(v))
    lib.flowMixedDestroy(host);offscreen.free()


def entry():
    try:run()
    except Exception:
        traceback.print_exc();sys.stdout.flush();sys.stderr.flush();os._exit(1)
    sys.stdout.flush();sys.stderr.flush();bpy.ops.wm.quit_blender()

# OpenGL context and VIEW_3D exist after Blender's first event-loop tick.
bpy.app.timers.register(entry,first_interval=1.)
