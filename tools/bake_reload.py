import struct, json, numpy as np
p='m4_-_fps_weapon_animations_pack_v_1.glb'
b=open(p,'rb').read()
off=12; J=None; BIN=None
while off<len(b):
    cl,ct=struct.unpack('<I4s',b[off:off+8]); off+=8
    if ct==b'JSON': J=json.loads(b[off:off+cl])
    elif ct[:3]==b'BIN': BIN=b[off:off+cl]
    off+=cl
nodes=J['nodes']
parent={}
for i,n in enumerate(nodes):
    for c in n.get('children',[]): parent[c]=i
def acc(i):
    a=J['accessors'][i]; bv=J['bufferViews'][a['bufferView']]
    nc={'SCALAR':1,'VEC3':3,'VEC4':4}[a['type']]
    dt={5126:np.float32,5123:np.uint16,5121:np.uint8,5120:np.int8,5122:np.int16}[a['componentType']]
    o=bv.get('byteOffset',0)+a.get('byteOffset',0)
    arr=np.frombuffer(BIN,dtype=dt,count=a['count']*nc,offset=o).reshape(a['count'],nc).astype(np.float64)
    if a.get('normalized'):
        arr=arr/{5123:65535,5121:255,5120:127,5122:32767}[a['componentType']]
    return arr
def qmat(q):
    x,y,z,w=q/np.linalg.norm(q)
    return np.array([[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)],[2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)],[2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]])
def slerp(a,b,t):
    d=np.dot(a,b)
    if d<0: b=-b; d=-d
    if d>0.9995:
        r=a+(b-a)*t; return r/np.linalg.norm(r)
    th=np.arccos(d); return (np.sin((1-t)*th)*a+np.sin(t*th)*b)/np.sin(th)
def clip(name):
    for a in J['animations']:
        if a['name']==name: return a
def sample_clip(a,t):
    local={i:{'t':np.array(n.get('translation',[0,0,0]),float),'r':np.array(n.get('rotation',[0,0,0,1]),float),'s':np.array(n.get('scale',[1,1,1]),float)} for i,n in enumerate(nodes)}
    for ch in a['channels']:
        s=a['samplers'][ch['sampler']]; ts=acc(s['input'])[:,0]; vs=acc(s['output'])
        path=ch['target']['path']; nd=ch['target']['node']
        tt=min(max(t,ts[0]),ts[-1]); k=np.searchsorted(ts,tt,side='right')-1; k=min(k,len(ts)-2)
        f=(tt-ts[k])/(ts[k+1]-ts[k]) if ts[k+1]>ts[k] else 0
        if path=='rotation': v=slerp(vs[k],vs[k+1],f)
        else: v=vs[k]+(vs[k+1]-vs[k])*f
        local[nd][{'translation':'t','rotation':'r','scale':'s'}[path]]=v
    return local
def world(local):
    W={}
    def get(i):
        if i in W: return W[i]
        n=nodes[i]
        if 'matrix' in n and i not in local: M=np.array(n['matrix']).reshape(4,4).T
        else:
            M=np.eye(4); M[:3,:3]=qmat(local[i]['r'])*local[i]['s']; M[:3,3]=local[i]['t']
        W[i]=(get(parent[i]) if i in parent else np.eye(4))@M
        return W[i]
    for i in range(len(nodes)): get(i)
    return W
def idx(prefix): return [i for i,n in enumerate(nodes) if n['name'].startswith(prefix)][0]

# ---- bake the Reload clip into the table used by ReloadAnim.kt (paste the printed numbers into D) ----
# Source: "M4 - FPS Weapon Animations Pack (v.1)" by BarcodeGames, CC-BY-4.0
#         https://sketchfab.com/3d-models/m4-fps-weapon-animations-pack-v1-662fc74dda2646cfb48fc610705768ef
# usage: put the .glb next to this file, python3 bake_reload.py
from scipy.spatial.transform import Rotation as R
a=clip('Reload')
ids={n:idx(n) for n in ['FPS_Camera','Main_j_049','Mag1_j_050','LeftHand_028','RightHand_05']}
cam=ids['FPS_Camera']
def pos(W,C,n): return (C@W[ids[n]])[:3,3]
W0=world(sample_clip(a,0)); C0=np.linalg.inv(W0[cam])
m0=pos(W0,C0,'Main_j_049'); r0=R.from_matrix((C0@W0[ids['Main_j_049']])[:3,:3])
F=56; DUR=3.6666667
for i in range(F):
    t=DUR*i/(F-1); W=world(sample_clip(a,t)); C=np.linalg.inv(W[cam])
    m=pos(W,C,'Main_j_049'); rr=R.from_matrix((C@W[ids['Main_j_049']])[:3,:3])
    eul=(rr*r0.inv()).as_euler('xyz',degrees=True)
    d=lambda n: pos(W,C,n)-m-(pos(W0,C0,n)-m0)
    row=list((m-m0)*0.01)+list(eul)+list(d('Mag1_j_050')*0.0125)+list(d('LeftHand_028')*0.0125)+list(d('RightHand_05')*0.0125)
    if i==F-1: row=[0]*15
    print(', '.join(f'{v:.4f}f' for v in row)+',')
