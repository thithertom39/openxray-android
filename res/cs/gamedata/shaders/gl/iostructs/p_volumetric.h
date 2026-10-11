
out vec4 SV_Target;
#ifdef MSAA_OPTIMIZATION
in int gl_SampleID;
#endif

struct 	v2p
{
	float3 	lightToPos	; // TEXCOORD0;	// light center to plane vector
	float3 	vPos		; // TEXCOORD1;	// position in camera space
	float 	fDensity	; // TEXCOORD2;	// plane density along Z axis
//	float2	tNoise 		; // TEXCOORD3;	// projective noise
#ifndef	USE_CLIP_DISTANCE
	float3	clip0		; // TEXCOORD3;
	float3	clip1		; // TEXCOORD4;
#endif	//	USE_CLIP_DISTANCE
};

layout(location = TEXCOORD0)	in float3 	v2p_lightToPos	; // TEXCOORD0;		// light center to plane vector
layout(location = TEXCOORD1)	in float3 	v2p_vPos		; // TEXCOORD1;		// position in camera space
layout(location = TEXCOORD2)	in float 	v2p_fDensity	; // TEXCOORD2;		// plane density along Z axis
//layout(location = TEXCOORD3)	in float2	v2p_tNoise 		; // TEXCOORD3;		// projective noise
#ifndef	USE_CLIP_DISTANCE
layout(location = TEXCOORD3)	in float3 	v2p_clip0		; // TEXCOORD3;
layout(location = TEXCOORD4)	in float3 	v2p_clip1		; // TEXCOORD4;
#endif	//	USE_CLIP_DISTANCE

#ifdef MSAA_OPTIMIZATION
float4 _main ( v2p I, uint iSample );
#else
float4 _main ( v2p I );
#endif

void main()
{
	v2p	I;
	I.lightToPos = v2p_lightToPos;
	I.vPos		= v2p_vPos;
	I.fDensity	= v2p_fDensity;
//	I.tNoise	= v2p_tNoise;
#ifndef	USE_CLIP_DISTANCE
	I.clip0		= v2p_clip0;
	I.clip1		= v2p_clip1;
	//	Fallback for drivers without gl_ClipDistance: a point is outside the
	//	light bounds if it falls on the negative side of any of the six planes.
	//	Matches what the fixed-function clipper does with gl_ClipDistance.
	if (any(lessThan(I.clip0, float3(0.0))) || any(lessThan(I.clip1, float3(0.0))))
		discard;
#endif	//	USE_CLIP_DISTANCE

#ifdef MSAA_OPTIMIZATION
	SV_Target	= _main ( I, gl_SampleID );
#else
	SV_Target	= _main ( I );
#endif
}
