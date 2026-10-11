#include "common.h"
#include	"iostructs\v_particle.h"

#ifdef	USE_SOFT_PARTICLES
uniform float4x4 	mVPTexgen;
#endif	//	USE_SOFT_PARTICLES

v2p _main (vv v)
{
	v2p 		o;

	o.hpos 		= mul	(m_WVP, v.P);		// xform, input in world coords
	o.hpos.z	= abs	(o.hpos.z);
	o.hpos.w	= abs	(o.hpos.w);
	o.tc		= v.tc;				// copy tc
	o.c		= v.c;				// copy color

#ifdef	USE_SOFT_PARTICLES
	o.tctexgen	= mul	(mVPTexgen, v.P);
	o.tctexgen.z	= o.hpos.z;
#endif	//	USE_SOFT_PARTICLES

	return o;
}
