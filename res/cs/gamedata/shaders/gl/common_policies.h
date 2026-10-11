#ifndef	common_policies_h_included
#define	common_policies_h_included

//	Define default sample index for MSAA
#ifndef	ISAMPLE
#define ISAMPLE uint(0)
#endif	//	ISAMPLE

//	redefine sample index
#ifdef 	MSAA_OPTIMIZATION
#undef	ISAMPLE
#define ISAMPLE	iSample
#endif	//	MSAA_OPTIMIZATION

/////////////////////////////////////////////////////////////////////////////
// GLD_P - gbuffer_load_data
// The callers pass struct members declared as float4 (e.g. v2p_volume::tc), but
// gbuffer_load_data only takes float2. HLSL truncated that implicitly, GLSL ES
// does not, so the .xy is explicit here.
#ifdef	GBUFFER_OPTIMIZATION
	#define	GLD_P( _tc, _pos2d, _iSample ) (_tc).xy, _pos2d, _iSample
#else	//	GBUFFER_OPTIMIZATION
	#define	GLD_P( _tc, _pos2d, _iSample ) (_tc).xy, _iSample
#endif	//	GBUFFER_OPTIMIZATION

/////////////////////////////////////////////////////////////////////////////
// CS_P
#ifdef USE_MSAA
#	ifdef	GBUFFER_OPTIMIZATION
#		define	CS_P( _P, _N, _tc0, _tcJ, _pos2d, _iSample ) _P, _N, _tc0, _tcJ, _pos2d, _iSample
#	else	//	GBUFFER_OPTIMIZATION
#		define	CS_P( _P, _N, _tc0, _tcJ, _pos2d, _iSample ) _P, _N, _tc0, _tcJ, _iSample
#	endif	//	GBUFFER_OPTIMIZATION
#else
#	ifdef	GBUFFER_OPTIMIZATION
#		define	CS_P( _P, _N, _tc0, _tcJ, _pos2d, _iSample ) _P, _N, _tc0, _tcJ, _pos2d
#	else	//	GBUFFER_OPTIMIZATION
#		define	CS_P( _P, _N, _tc0, _tcJ, _pos2d, _iSample ) _P, _N, _tc0, _tcJ
#	endif
#endif

#endif	//	common_policies_h_included
