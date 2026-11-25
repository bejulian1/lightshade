package no.julian.lightshade.render;

/** The loader's own fullscreen vertex program for graph passes. Pack shaders live in packs/. */
final class BuiltinShaders {
    private BuiltinShaders(){}
    static final String FULLSCREEN="""
        #version 450
        layout(location=0) out vec2 uv;
        void main(){
            int corner=gl_VertexIndex;
            uv=vec2(float((corner<<1)&2),float(corner&2));
            gl_Position=vec4(uv*2.0-1.0,0.0,1.0);
        }
        """;
}
