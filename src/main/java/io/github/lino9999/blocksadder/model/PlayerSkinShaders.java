package io.github.lino9999.blocksadder.model;

final class PlayerSkinShaders {
    private PlayerSkinShaders() {}

    static String vertex(int format) {
        boolean modern = format >= 97;
        String prefix = "#version 330\n" + (modern ? "#extension GL_ARB_separate_shader_objects : require\n" : "");
        String directive = modern ? "#include" : "#moj_import";
        StringBuilder source = new StringBuilder(prefix);
        for (String name : new String[]{"light", "fog", "dynamictransforms", "projection"}) source.append(directive).append(" <minecraft:").append(name).append(".glsl>\n");
        if (format >= 84) source.append(directive).append(" <minecraft:sample_lightmap.glsl>\n");
        String[] attributes = {"vec3 Position", "vec4 Color", "vec2 UV0", "ivec2 UV1", "ivec2 UV2", "vec3 Normal"};
        for (int index = 0; index < attributes.length; index++) source.append(declaration(modern, index, "in", attributes[index]));
        source.append("#ifndef NO_OVERLAY\nuniform sampler2D Sampler1;\n#endif\n#ifndef EMISSIVE\nuniform sampler2D Sampler2;\n#endif\n");
        varying(source, modern, "out");
        source.append("""
                void main() {
                    vec3 position = Position;
                    int candidate = int(floor((-position.y + 512.0) / 1024.0)) - 1;
                    baPart = -1;
                    if (abs(position.x - 4096.0) <= 384.0 && candidate >= 0 && candidate < 24 && abs(position.y + 1024.0 * float(candidate + 1)) <= 384.0) {
                        baPart = candidate;
                        position += vec3(-4096.0, 1024.0 * float(candidate + 1), 0.0);
                    }
                    gl_Position = ProjMat * ModelViewMat * vec4(position, 1.0);
                    baSphere = fog_spherical_distance(position);
                    baCylinder = fog_cylindrical_distance(position);
                #ifdef PER_FACE_LIGHTING
                    vec2 amount = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
                    baFront = minecraft_mix_light_separate(amount, Color);
                    baBack = minecraft_mix_light_separate(-amount, Color);
                #elif defined(NO_CARDINAL_LIGHTING)
                    baFront = Color;
                    baBack = Color;
                #else
                    baFront = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
                    baBack = baFront;
                #endif
                    baLight = vec4(1.0);
                    baOverlay = vec4(1.0);
                """);
        source.append("#if !defined(EMISSIVE)").append(modern ? " && !defined(OIT_ALPHA_ONLY)" : "").append("\n    baLight = ")
                .append(format >= 84 ? "sample_lightmap(Sampler2, UV2)" : "texelFetch(Sampler2, UV2 / 16, 0)").append(";\n#endif\n");
        source.append("#if !defined(NO_OVERLAY)").append(modern ? " && !defined(OIT_ALPHA_ONLY)" : "").append("\n    baOverlay = texelFetch(Sampler1, UV1, 0);\n#endif\n");
        source.append("""
                    baUv = UV0;
                #ifdef APPLY_TEXTURE_MATRIX
                    baUv = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
                #endif
                """);
        if (modern) source.append("#ifdef GLINT\n    baGlint = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;\n#endif\n");
        return source.append("}\n").toString();
    }

    static String fragment(int format) {
        boolean modern = format >= 97;
        StringBuilder source = new StringBuilder("#version 330\n");
        if (modern) source.append("#extension GL_ARB_separate_shader_objects : require\n");
        String directive = modern ? "#include" : "#moj_import";
        for (String name : new String[]{"fog", "dynamictransforms"}) source.append(directive).append(" <minecraft:").append(name).append(".glsl>\n");
        if (modern) source.append("#ifdef GLINT\n#include <minecraft:globals.glsl>\n#endif\n#include <minecraft:oit.glsl>\n");
        source.append("uniform sampler2D Sampler0;\n");
        if (modern) source.append("#ifdef DISSOLVE\nuniform sampler2D DissolveMaskSampler;\n#endif\n#ifdef GLINT\nuniform sampler2D GlintSampler;\n#endif\n");
        varying(source, modern, "in");
        source.append(modern ? "#ifndef OIT_ALPHA_ONLY\nlayout(location = 0) out vec4 fragColor;\n#endif\n" : "out vec4 fragColor;\n");
        source.append("""
                vec2 baSkinUv(vec2 uv, int code) {
                    vec2 pixel = uv * 64.0;
                    if (pixel.x >= 32.0 || pixel.y >= 16.0 || pixel.x < 0.0 || pixel.y < 0.0) discard;
                    int part = code % 6;
                    bool slim = (code / 6) % 2 != 0;
                    bool outer = code >= 12;
                    float width = part < 2 ? 8.0 : (slim && part < 4 ? 3.0 : 4.0);
                    float height = part == 0 ? 8.0 : 12.0;
                    float depth = part == 0 ? 8.0 : 4.0;
                    vec2 origin;
                    if (part == 0) origin = outer ? vec2(32.0, 0.0) : vec2(0.0);
                    else if (part == 1) origin = outer ? vec2(16.0, 32.0) : vec2(16.0, 16.0);
                    else if (part == 2) origin = outer ? vec2(48.0, 48.0) : vec2(32.0, 48.0);
                    else if (part == 3) origin = outer ? vec2(40.0, 32.0) : vec2(40.0, 16.0);
                    else if (part == 4) origin = outer ? vec2(0.0, 48.0) : vec2(16.0, 48.0);
                    else origin = outer ? vec2(0.0, 32.0) : vec2(0.0, 16.0);
                    vec2 mapped;
                    if (pixel.y < 8.0) {
                        bool bottom = pixel.x >= 16.0;
                        mapped = vec2(depth + (bottom ? width : 0.0) + (pixel.x - (bottom ? 16.0 : 8.0)) * width / 8.0, pixel.y * depth / 8.0);
                    } else {
                        int face = min(3, int(pixel.x / 8.0));
                        float begin = face == 0 ? 0.0 : face == 1 ? depth : face == 2 ? depth + width : depth * 2.0 + width;
                        mapped = vec2(begin + (pixel.x / 8.0 - float(face)) * (face % 2 == 0 ? depth : width), depth + (pixel.y - 8.0) * height / 8.0);
                    }
                    return (origin + mapped) / 64.0;
                }
                void main() {
                    vec2 uv = baPart < 0 ? baUv : baSkinUv(baUv, baPart);
                    vec4 result = texture(Sampler0, uv);
                """);
        if (modern) source.append("#ifdef OIT_ADDITIVE\n    result.a = min(result.a, 0.99);\n#endif\n");
        source.append("""
                #ifdef ALPHA_CUTOUT
                    if (result.a < ALPHA_CUTOUT) discard;
                #endif
                    vec4 illumination = gl_FrontFacing ? baFront : baBack;
                """);
        if (modern) source.append("#ifdef DISSOLVE\n    if (illumination.a < texture(DissolveMaskSampler, baUv).a) discard;\n    illumination.a = 1.0;\n#endif\n");
        source.append("    result *= illumination * ColorModulator;\n");
        if (modern) source.append("#ifdef GLINT\n    result.a = max(result.a, GlintAlpha);\n#endif\n#ifdef OIT_ALPHA_ONLY\n    executeAlphaOnlyPhase(gl_FragCoord.z, result.a);\n#else\n");
        source.append("#ifndef NO_OVERLAY\n    result.rgb = mix(baOverlay.rgb, result.rgb, baOverlay.a);\n#endif\n#ifndef EMISSIVE\n    result *= baLight;\n#endif\n");
        if (modern) source.append("#ifdef GLINT\n    vec3 shine = (GlintAlpha * texture(GlintSampler, baGlint)).rgb;\n    result.rgb += shine * shine;\n#endif\n#ifdef OIT_ACCUMULATE\n    result = sampleColorForAccumulation(result);\n    vec4 fog = vec4(FogColor.rgb * result.a, FogColor.a);\n#else\n    vec4 fog = FogColor;\n#endif\n");
        else source.append("    vec4 fog = FogColor;\n");
        source.append("    fragColor = apply_fog(result, baSphere, baCylinder, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fog);\n");
        if (modern) source.append("#endif\n");
        return source.append("}\n").toString();
    }

    private static String declaration(boolean modern, int location, String direction, String type) {
        return (modern ? "layout(location = " + location + ") " : "") + direction + " " + type + ";\n";
    }

    private static void varying(StringBuilder source, boolean modern, String direction) {
        String[] fields = {"float baSphere", "float baCylinder", "vec4 baFront", "vec4 baBack", "vec4 baLight", "vec4 baOverlay", "vec2 baUv"};
        for (int index = 0; index < fields.length; index++) source.append(declaration(modern, index, direction, fields[index]));
        if (modern) source.append("#ifdef GLINT\n").append(declaration(true, 7, direction, "vec2 baGlint")).append("#endif\n");
        source.append(modern ? "layout(location = 8) " : "").append("flat ").append(direction).append(" int baPart;\n");
    }
}
