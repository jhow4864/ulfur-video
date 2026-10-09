import math, sys, json
wolf=open('wolf.txt').read()
EYE="M43,35 Q45.8,35.8 48.4,38 Q45.4,37.6 43.4,36.2Z"

def rounded_poly(pts, r):
    n=len(pts); d=[]
    segs=[]
    for i in range(n):
        p0=pts[i-1]; p=pts[i]; p1=pts[(i+1)%n]
        v0=(p0[0]-p[0],p0[1]-p[1]); v1=(p1[0]-p[0],p1[1]-p[1])
        l0=math.hypot(*v0); l1=math.hypot(*v1)
        u0=(v0[0]/l0,v0[1]/l0); u1=(v1[0]/l1,v1[1]/l1)
        ang=math.acos(max(-1,min(1,u0[0]*u1[0]+u0[1]*u1[1])))
        t=r/math.tan(ang/2)
        a=(p[0]+u0[0]*t,p[1]+u0[1]*t); b=(p[0]+u1[0]*t,p[1]+u1[1]*t)
        segs.append((a,p,b))
    s="M%.2f,%.2f "%segs[0][2]
    for i in range(1,n+1):
        a,p,b=segs[i%n]
        s+="L%.2f,%.2f Q%.2f,%.2f %.2f,%.2f "%(a[0],a[1],p[0],p[1],b[0],b[1])
    return s+"Z"

CFG=dict(tri=[(20,8),(20,92),(90,50)], r=10, ws=0.95, wx=-4, wy=-2)
if len(sys.argv)>1: CFG.update(json.loads(sys.argv[1]))
TRI=rounded_poly(CFG['tri'],CFG['r'])
WT="translate(%g %g) scale(%g)"%(CFG['wx'],CFG['wy'],CFG['ws'])
# map art space (0..100) into 108 viewport inside safe zone
G=CFG.get('g',"translate(17 15) scale(.74)")

defs='''<defs>
<linearGradient id="neon" gradientUnits="userSpaceOnUse" x1="20" y1="8" x2="80" y2="92"><stop offset="0" stop-color="#FF2D55"/><stop offset=".55" stop-color="#F72FA0"/><stop offset="1" stop-color="#E040FB"/></linearGradient>
<radialGradient id="bg" gradientUnits="userSpaceOnUse" cx="50" cy="43" r="67"><stop offset="0" stop-color="#2A1A2C"/><stop offset=".55" stop-color="#17151D"/><stop offset="1" stop-color="#121218"/></radialGradient>
<clipPath id="tri"><path d="%s"/></clipPath>
</defs>'''%TRI
fg='''<g transform="%s"><path d="%s" fill="url(#neon)"/><g clip-path="url(#tri)"><g transform="%s"><path d="%s" fill="#121218"/></g></g><path d="%s" fill="none" stroke="url(#neon)" stroke-width="%g"/><g transform="%s"><path d="%s" fill="#FF7A9A"/></g></g>'''%(G,TRI,WT,wolf,TRI,CFG.get('sw',3),WT,EYE)
mono='''<g transform="%s"><path d="%s" fill="#000"/><g clip-path="url(#tri)"><g transform="%s"><path d="%s" fill="#fff"/></g></g></g>'''%(G,TRI,WT,wolf)
bg='<rect width="108" height="108" fill="url(#bg)"/>'
def svg(body,size=512): return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="%d" height="%d">%s%s</svg>'%(size,size,defs,body)
open('full.svg','w').write(svg(bg+fg))
open('fg.svg','w').write(svg(fg))
open('bg.svg','w').write(svg(bg))
open('mono.svg','w').write(svg('<rect width="108" height="108" fill="#fff"/>'+mono))
json.dump(dict(TRI=TRI,WT=[CFG['wx'],CFG['wy'],CFG['ws']],G=G),open('cfg.json','w'))
