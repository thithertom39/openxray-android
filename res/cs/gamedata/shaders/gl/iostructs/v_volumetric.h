
#ifdef	USE_CLIP_DISTANCE
//	Redeclaring gl_PerVertex to add gl_ClipDistance is only legal when the driver
//	exposes GL_EXT_clip_cull_distance. Mali-G57 rejects it outright ("Member not
//	part of the original block 'gl_PerVertex'"), so the clip distances are passed
//	to the pixel shader instead and tested there.
out gl_PerVertex
{
	vec4 gl_Position;
	float gl_ClipDistance[6];
};
#else	//	USE_CLIP_DISTANCE
out gl_PerVertex
{
	vec4 gl_Position;
};
#endif	//	USE_CLIP_DISTANCE

struct v2p
{
	float3 	lightToPos	; // TEXCOORD0;		// light center to plane vector
	float3 	vPos		; // TEXCOORD1;		// position in camera space
	float 	fDensity	; // TEXCOORD2;		// plane density alon Z axis
//	float2	tNoise 		; // TEXCOORD3;		// projective noise
	float3	clip0		; // SV_ClipDistance0;
	float3	clip1		; // SV_ClipDistance1;
	float4 	hpos		; // SV_Position;
};

layout(location = POSITION)		in float3	v_volumetric_P;

layout(location = TEXCOORD0)	out float3 	v2p_lightToPos	; // TEXCOORD0;		// light center to plane vector
layout(location = TEXCOORD1)	out float3 	v2p_vPos		; // TEXCOORD1;		// position in camera space
layout(location = TEXCOORD2)	out float 	v2p_fDensity	; // TEXCOORD2;		// plane density alon Z axis
//layout(location = TEXCOORD3)	out float2	v2p_tNoise 		; // TEXCOORD3;		// projective noise
#ifndef	USE_CLIP_DISTANCE
layout(location = TEXCOORD3)	out float3 	v2p_clip0		; // TEXCOORD3;
layout(location = TEXCOORD4)	out float3 	v2p_clip1		; // TEXCOORD4;
#endif	//	USE_CLIP_DISTANCE

v2p _main ( float3 P );

void main()
{
	v2p O	= _main ( v_volumetric_P );
	v2p_lightToPos	= O.lightToPos;
	v2p_vPos		= O.vPos;
	v2p_fDensity	= O.fDensity;
//	v2p_tNoise		= O.tNoise;
	gl_Position		= O.hpos;
#ifdef	USE_CLIP_DISTANCE
	for (int i=0; i<3; ++i)
	{
		gl_ClipDistance[i] = O.clip0[i];
		gl_ClipDistance[i+3] = O.clip1[i];
	}
#else	//	USE_CLIP_DISTANCE
	v2p_clip0	= O.clip0;
	v2p_clip1	= O.clip1;
#endif	//	USE_CLIP_DISTANCE
}
