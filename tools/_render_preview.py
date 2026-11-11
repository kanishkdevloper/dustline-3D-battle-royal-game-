import numpy as np
SUN=np.array([-0.5652,0.6935,0.3596])
def normals(pos, idx):
    nr=np.zeros_like(pos)
    a,b,c=pos[idx[:,0]],pos[idx[:,1]],pos[idx[:,2]]
    fn=np.cross(b-a,c-a)
    for k in range(3): np.add.at(nr, idx[:,k], fn)
    l=np.linalg.norm(nr,axis=1,keepdims=True); l[l<1e-12]=1
    return nr/l
def rotm(axis, ang):
    c,s=np.cos(ang),np.sin(ang); x,y,z=axis
    R=np.array([[c+x*x*(1-c),x*y*(1-c)-z*s,x*z*(1-c)+y*s],[y*x*(1-c)+z*s,c+y*y*(1-c),y*z*(1-c)-x*s],[z*x*(1-c)-y*s,z*y*(1-c)+x*s,c+z*z*(1-c)]])
    return R
def joint_rot(pivot, axis, ang):
    M=np.eye(4); M[:3,:3]=rotm(axis,ang); p=np.array(pivot); M[:3,3]=p-M[:3,:3]@p; return M
JOINT={'thighL':(-.12,1.04,0),'thighR':(.12,1.04,0),'shinL':(-.12,.54,-.015),'shinR':(.12,.54,-.015),
       'upperL':(-.27,1.67,0),'upperR':(.27,1.67,0),'foreL':(-.328,1.305,.022),'foreR':(.328,1.305,.022)}
BONES=['body','thighL','thighR','shinL','shinR','upperL','upperR','foreL','foreR','handL','handR']
def pose_mats(angles):
    """angles: dict bone-> (axis, ang). returns 11 4x4 world skin matrices (child inherits parent)."""
    M=[np.eye(4) for _ in range(11)]
    par={'thighL':'body','thighR':'body','shinL':'thighL','shinR':'thighR','upperL':'body','upperR':'body','foreL':'upperL','foreR':'upperR','handL':'foreL','handR':'foreR'}
    local={}
    for i,b in enumerate(BONES):
        if b in angles:
            ax,an=angles[b]; local[b]=joint_rot(JOINT.get(b,JOINT.get('fore'+b[-1]) if b.startswith('hand') else (0,0,0)),ax,an)
        else: local[b]=np.eye(4)
    done={}
    def get(b):
        if b in done: return done[b]
        r=local[b] if b not in par else get(par[b])@local[b]
        done[b]=r; return r
    return [get(b) for b in BONES]
def skin(pos, si, sw, mats):
    mats=np.array(mats); w=sw/np.maximum(sw.sum(1,keepdims=True),1e-9)
    M=np.zeros((len(pos),4,4))
    for k in range(4): M+=w[:,k,None,None]*mats[si[:,k]]
    p=np.concatenate([pos,np.ones((len(pos),1))],1)
    return np.einsum('nij,nj->ni',M,p)[:,:3], M
def skin_nor(nor, M):
    return np.einsum('nij,nj->ni',M[:,:3,:3],nor)
class Canvas:
    def __init__(s,W,H,bg=(70,78,98),ss=2):
        s.W,s.H,s.ss=W*ss,H*ss,ss; s.w,s.h=W,H
        yy=np.linspace(0,1,s.H)[:,None,None]
        top=np.array(bg)*1.15; bot=np.array(bg)*0.85
        s.img=(top*(1-yy)+bot*yy)*np.ones((s.H,s.W,3))/255.0
        s.z=np.full((s.H,s.W),1e9)
    def tri(s, P, col, Z):
        x0=int(max(0,np.floor(P[:,0].min()))); x1=int(min(s.W-1,np.ceil(P[:,0].max())))
        y0=int(max(0,np.floor(P[:,1].min()))); y1=int(min(s.H-1,np.ceil(P[:,1].max())))
        if x1<x0 or y1<y0: return
        xs=np.arange(x0,x1+1)+0.5; ys=np.arange(y0,y1+1)+0.5
        X,Y=np.meshgrid(xs,ys)
        d=(P[1,1]-P[2,1])*(P[0,0]-P[2,0])+(P[2,0]-P[1,0])*(P[0,1]-P[2,1])
        if abs(d)<1e-12: return
        l0=((P[1,1]-P[2,1])*(X-P[2,0])+(P[2,0]-P[1,0])*(Y-P[2,1]))/d
        l1=((P[2,1]-P[0,1])*(X-P[2,0])+(P[0,0]-P[2,0])*(Y-P[2,1]))/d
        l2=1-l0-l1
        m=(l0>=-1e-6)&(l1>=-1e-6)&(l2>=-1e-6)
        if not m.any(): return
        z=l0*Z[0]+l1*Z[1]+l2*Z[2]
        zb=s.z[y0:y1+1,x0:x1+1]; upd=m&(z<zb)
        if not upd.any(): return
        c=(l0[...,None]*col[0]+l1[...,None]*col[1]+l2[...,None]*col[2])
        s.z[y0:y1+1,x0:x1+1]=np.where(upd,z,zb)
        s.img[y0:y1+1,x0:x1+1]=np.where(upd[...,None],c,s.img[y0:y1+1,x0:x1+1])
    def draw(s, pos, nor, idx, col, cam, eye=None):
        """pos world (n,3); nor world; col (n,3) 0..1 ; cam: dict with yaw,pitch,dist,center,fov. Game shader."""
        yaw,pitch,dist,center,scale=cam['yaw'],cam['pitch'],cam['dist'],np.array(cam['center']),cam['scale']
        R=rotm((0,1,0),yaw)@rotm((1,0,0),pitch)
        q=(pos-center)@R.T
        # view looking down -z from +z : camera at z=+dist ; ortho-ish with mild perspective
        zc=dist-q[:,2]
        f=scale/ zc* dist
        sx=s.W/2+q[:,0]*f; sy=s.H/2-q[:,1]*f
        n=nor@R.T
        # shading like the game's fragment shader (n flipped if back facing)
        # sun direction in world -> rotate with the same view R (camera orbits model)
        sun=SUN@R.T
        for t in idx:
            a,b,c=t
            P=np.array([[sx[a],sy[a]],[sx[b],sy[b]],[sx[c],sy[c]]])
            Z=-zc[[a,b,c]]*-1
            Z=np.array([zc[a],zc[b],zc[c]])
            # facing
            area=(P[1,0]-P[0,0])*(P[2,1]-P[0,1])-(P[2,0]-P[0,0])*(P[1,1]-P[0,1])
            cs=[]
            for v in (a,b,c):
                nn=n[v].copy()
                # gl_FrontFacing: CCW in GL (y up) -> in screen coords (y down) CCW means area<0
                front = area<0
                if not front: nn=-nn
                hemi=np.array([0.49,0.54,0.31])*(1-(0.5+0.5*nn[1]))+np.array([0.81,0.89,1.0])*(0.5+0.5*nn[1])
                lit=col[v]*(hemi*0.62+np.array([1.0,0.91,0.78])*max(np.dot(nn,sun),0)*0.95)+np.array([0.12,0.115,0.10])*col[v]
                cs.append(np.clip(lit,0,1))
            s.tri(P,cs,Z)
    def save(s,path,sz=None):
        from PIL import Image
        im=Image.fromarray((np.clip(s.img,0,1)*255).astype(np.uint8))
        im=im.resize((s.w,s.h),Image.LANCZOS)
        if sz: im=im.resize((sz,sz),Image.LANCZOS)
        im.save(path); return im
