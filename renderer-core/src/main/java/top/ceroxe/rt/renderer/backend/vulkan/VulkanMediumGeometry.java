package top.ceroxe.rt.renderer.backend.vulkan;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import top.ceroxe.rt.renderer.api.AffineTransform;
import top.ceroxe.rt.renderer.api.MeshAsset;

/** Immutable triangle BVH shared by every boundary instance of one accepted mesh generation. */
final class VulkanMediumGeometry {
    private static final int AMBIGUOUS = Integer.MIN_VALUE;
    private static final double[][] DIRECTIONS = {{1,.3713906763541037,.6947465906068658},
            {.537190783,1,.219377619},{.129831703,.66137191,1}};
    private final FloatBuffer points;
    private final IntBuffer indices;
    private final int[] order;
    private final ArrayList<Node> nodes = new ArrayList<>();
    private final double scale;

    VulkanMediumGeometry(MeshAsset mesh) {
        points=mesh.positions();indices=mesh.triangleIndices();
        order=new int[mesh.triangleCount()];
        for(int i=0;i<order.length;i++) order[i]=i;
        build(0,order.length);
        Node root=nodes.getFirst();
        scale=Math.max(root.maxX-root.minX,Math.max(root.maxY-root.minY,root.maxZ-root.minZ));
    }

    boolean contains(AffineTransform transform,double x,double y,double z) {
        double a=transform.element(0),b=transform.element(1),c=transform.element(2);
        double d=transform.element(4),e=transform.element(5),f=transform.element(6);
        double g=transform.element(8),h=transform.element(9),i=transform.element(10);
        double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
        double px=x-transform.element(3),py=y-transform.element(7),pz=z-transform.element(11);
        return containsLocal(((e*i-f*h)*px+(c*h-b*i)*py+(b*f-c*e)*pz)/det,
                ((f*g-d*i)*px+(a*i-c*g)*py+(c*d-a*f)*pz)/det,
                ((d*h-e*g)*px+(b*g-a*h)*py+(a*e-b*d)*pz)/det);
    }

    boolean containsLocal(double x,double y,double z) {
        Node root=nodes.getFirst();
        if(x<root.minX || x>root.maxX || y<root.minY || y>root.maxY || z<root.minZ || z>root.maxZ) return false;
        for(double[] direction:DIRECTIONS) {
            int winding=crossings(0,x,y,z,direction[0],direction[1],direction[2]);
            if(winding!=AMBIGUOUS) return winding!=0;
        }
        // Edge/vertex intersections use the original generalized-winding fallback. The common
        // case visits only intersected BVH leaves and never scans the whole triangle buffer.
        return windingContains(x,y,z);
    }

    private int build(int start,int end) {
        Node node=new Node(start,end);int slot=nodes.size();nodes.add(node);
        for(int n=start;n<end;n++) for(int vertex=0;vertex<3;vertex++) {
            int at=indices.get(order[n]*3+vertex)*3;
            double x=points.get(at),y=points.get(at+1),z=points.get(at+2);
            node.minX=Math.min(node.minX,x);node.maxX=Math.max(node.maxX,x);
            node.minY=Math.min(node.minY,y);node.maxY=Math.max(node.maxY,y);
            node.minZ=Math.min(node.minZ,z);node.maxZ=Math.max(node.maxZ,z);
        }
        if(end-start>8) {
            double dx=node.maxX-node.minX,dy=node.maxY-node.minY,dz=node.maxZ-node.minZ;
            int axis=dx>=dy && dx>=dz?0:dy>=dz?1:2,mid=(start+end)>>>1;
            select(start,end-1,mid,axis);
            node.left=build(start,mid);node.right=build(mid,end);
        }
        return slot;
    }

    private double centroid(int triangle,int axis) {
        int at=triangle*3;
        return (double)points.get(indices.get(at)*3+axis)+points.get(indices.get(at+1)*3+axis)
                +points.get(indices.get(at+2)*3+axis);
    }

    private void select(int low,int high,int target,int axis) {
        while(low<high) {
            double pivot=centroid(order[(low+high)>>>1],axis);int left=low,right=high;
            while(left<=right) {
                while(centroid(order[left],axis)<pivot) left++;
                while(centroid(order[right],axis)>pivot) right--;
                if(left<=right) {int swap=order[left];order[left++]=order[right];order[right--]=swap;}
            }
            if(target<=right) high=right;else if(target>=left) low=left;else return;
        }
    }

    private int crossings(int slot,double x,double y,double z,double dx,double dy,double dz) {
        Node n=nodes.get(slot);
        double near=Math.max(0,Math.max(Math.min((n.minX-x)/dx,(n.maxX-x)/dx),
                Math.max(Math.min((n.minY-y)/dy,(n.maxY-y)/dy),Math.min((n.minZ-z)/dz,(n.maxZ-z)/dz))));
        double far=Math.min(Math.max((n.minX-x)/dx,(n.maxX-x)/dx),
                Math.min(Math.max((n.minY-y)/dy,(n.maxY-y)/dy),Math.max((n.minZ-z)/dz,(n.maxZ-z)/dz)));
        if(far<near) return 0;
        if(n.left>=0) {
            int left=crossings(n.left,x,y,z,dx,dy,dz);
            if(left==AMBIGUOUS) return AMBIGUOUS;
            int right=crossings(n.right,x,y,z,dx,dy,dz);
            return right==AMBIGUOUS?AMBIGUOUS:left+right;
        }
        int total=0;
        for(int triangle=n.start;triangle<n.end;triangle++) {
            int at=order[triangle]*3,a=indices.get(at)*3,b=indices.get(at+1)*3,c=indices.get(at+2)*3;
            double ax=points.get(a),ay=points.get(a+1),az=points.get(a+2);
            double ex=points.get(b)-ax,ey=points.get(b+1)-ay,ez=points.get(b+2)-az;
            double fx=points.get(c)-ax,fy=points.get(c+1)-ay,fz=points.get(c+2)-az;
            double hx=dy*fz-dz*fy,hy=dz*fx-dx*fz,hz=dx*fy-dy*fx;
            double det=ex*hx+ey*hy+ez*hz;
            double normalLength=Math.sqrt(hx*hx+hy*hy+hz*hz)*Math.sqrt(ex*ex+ey*ey+ez*ez);
            if(Math.abs(det)<=normalLength*1e-13) continue;
            double tx=x-ax,ty=y-ay,tz=z-az,u=(tx*hx+ty*hy+tz*hz)/det;
            if(u < -1e-10 || u>1+1e-10) continue;
            double qx=ty*ez-tz*ey,qy=tz*ex-tx*ez,qz=tx*ey-ty*ex;
            double v=(dx*qx+dy*qy+dz*qz)/det;
            if(v < -1e-10 || u+v>1+1e-10) continue;
            double distance=(fx*qx+fy*qy+fz*qz)/det;
            if(distance < -scale*1e-12) continue;
            if(distance<=scale*1e-12 || u<=1e-10 || v<=1e-10 || 1-u-v<=1e-10) return AMBIGUOUS;
            total+=det<0?1:-1;
        }
        return total;
    }

    boolean windingContains(double x,double y,double z) {
        double solidAngle=0;
        for(int triangle=0;triangle<indices.limit();triangle+=3) {
            int a=indices.get(triangle)*3,b=indices.get(triangle+1)*3,c=indices.get(triangle+2)*3;
            double ax=points.get(a)-x,ay=points.get(a+1)-y,az=points.get(a+2)-z;
            double bx=points.get(b)-x,by=points.get(b+1)-y,bz=points.get(b+2)-z;
            double cx=points.get(c)-x,cy=points.get(c+1)-y,cz=points.get(c+2)-z;
            double al=Math.sqrt(ax*ax+ay*ay+az*az),bl=Math.sqrt(bx*bx+by*by+bz*bz),cl=Math.sqrt(cx*cx+cy*cy+cz*cz);
            if(al==0 || bl==0 || cl==0) return true;
            double numerator=ax*(by*cz-bz*cy)+ay*(bz*cx-bx*cz)+az*(bx*cy-by*cx);
            double denominator=al*bl*cl+(ax*bx+ay*by+az*bz)*cl+(bx*cx+by*cy+bz*cz)*al+(cx*ax+cy*ay+cz*az)*bl;
            solidAngle+=2*Math.atan2(numerator,denominator);
        }
        return Math.abs(solidAngle)>2*Math.PI;
    }

    private static final class Node {
        final int start,end;
        int left=-1,right=-1;
        double minX=Double.POSITIVE_INFINITY,minY=minX,minZ=minX;
        double maxX=Double.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        Node(int start,int end) {this.start=start;this.end=end;}
    }
}
