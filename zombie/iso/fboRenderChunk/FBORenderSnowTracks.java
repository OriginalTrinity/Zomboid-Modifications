package zombie.iso.fboRenderChunk;

import zombie.IndieGL;
import zombie.core.Color;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLState;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.debug.DebugOptions;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.SnowTracks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Consumer;

public final class FBORenderSnowTracks {
    private static final int GL_ZERO = 0;
    private static final int GL_ONE = 1;
    private static final int GL_ONE_MINUS_SRC_COLOR = 769;
    private static final int GL_SRC_ALPHA = 770;
    private static final int GL_ONE_MINUS_SRC_ALPHA = 771;
    private static final int GL_ONE_MINUS_DST_ALPHA = 773;
    private static final int GL_LEQUAL = 515;
    private static final int GL_NOTEQUAL = 517;
    private static final int GL_STENCIL_TEST = 2960;
    private static final int GL_KEEP = 7680;
    private static final int GL_REPLACE = 7681;
    private static final int RUT_STENCIL_BIT = 1; // vanilla uses 128
    private static final Comparator<SnowTracks.Track> NEWEST_FIRST = (a, b) -> Double.compare(b.getCreatedAt(), a.getCreatedAt());
    private static final float FULL_STRENGTH_UNTIL = 0.4f;
    private static final float MIN_STRENGTH = 0.01f;
    private static final long SEEN_FADE_MS = 500; // tracks coming into view fade in over this
    private static final float DARKNESS = 0.40f; // scales the textures' darkening
    private static final float DEPTH_BIAS = 0.25f; // keeps tracks in front of the floor, like Render3DItem
    private static final float FLOOR_BIAS = 0.05f; // the same with a depth per corner, which follows the floor exactly
    private static final float FLOOR_DEPTH_STEP = -IsoDepthHelper.CHUNK_DEPTH / 8.0f; // per square of x + y, as GL depth
    private static final int ATLAS_GUTTER = 2; // transparent pixel on each side of a variant
    private static final int SIDES_CACHE = 64; // power of two
    private static final String FOOTPRINT_ATLAS = "media/textures/SnowTracks/Footprints.png";
    private static final String TIRE_ATLAS = "media/textures/SnowTracks/TireTracks.png";
    private static final String CARRIED_FOOTPRINT_ATLAS = "media/textures/SnowTracks/CarriedFootprints.png";
    private static final String CARRIED_TIRE_ATLAS = "media/textures/SnowTracks/CarriedTireTracks.png";
    private static final FBORenderSnowTracks instance = new FBORenderSnowTracks();

    private Texture footprints;
    private Texture tireTracks;
    private Texture carriedFootprints;
    private Texture carriedTireTracks;
    private boolean resourcesLoaded;
    private TrackShader shader;
    private boolean batched;
    private int playerIndex;
    private final AtlasQuad atlasQuad = new AtlasQuad();
    private final ArrayList<SnowTracks.Track> visiblePrints = new ArrayList<>();
    private final ArrayList<SnowTracks.Track> visibleTires = new ArrayList<>();
    private final float[] screenX = new float[4];
    private final float[] screenY = new float[4];
    private final float[] worldX = new float[4];
    private final float[] worldY = new float[4];
    private float jiggleX;
    private float jiggleY;
    private float lightR, lightG, lightB;
    // Snow sides of recently looked up squares, valid for one frame
    private final int[] cacheX = new int[SIDES_CACHE];
    private final int[] cacheY = new int[SIDES_CACHE];
    private final int[] cacheZ = new int[SIDES_CACHE];
    private final int[] cacheSides = new int[SIDES_CACHE];
    private final int[] cacheFrame = new int[SIDES_CACHE];
    private int frame;

    private FBORenderSnowTracks() {}

    public static FBORenderSnowTracks getInstance() {
        return instance;
    }

    public void render(int playerIndex) {
        this.loadResources();
        SnowTracks.Layer prints = SnowTracks.getPrints();
        SnowTracks.Layer tires = SnowTracks.getTires();
        if (prints.isEmpty() && tires.isEmpty()) {
            return;
        }

        // Without the shader (not compiled yet, or its files missing) each quad is its own batch
        this.batched = this.shader.isCompiled();
        this.playerIndex = playerIndex;
        this.frame++;
        // The chunk textures are shifted by the camera's jiggle fix, so the tracks on them have to be too
        boolean shifted = !DebugOptions.instance.fboRenderChunk.combinedFbo.getValue();
        this.jiggleX = shifted ? IsoCamera.cameras[playerIndex].fixJigglyModelsSquareX : 0.0f;
        this.jiggleY = shifted ? IsoCamera.cameras[playerIndex].fixJigglyModelsSquareY : 0.0f;
        int level = PZMath.fastfloor(IsoCamera.frameState.camCharacterZ);
        this.collectVisible(playerIndex, level, prints, tires);
        if (this.visiblePrints.isEmpty() && this.visibleTires.isEmpty()) {
            return;
        }

        IndieGL.glDepthMask(false);
        IndieGL.enableDepthTest();
        IndieGL.glDepthFunc(GL_LEQUAL);
        IndieGL.glBlendFuncSeparate(GL_ZERO, GL_ONE_MINUS_SRC_COLOR, GL_ZERO, GL_ONE);
        if (this.batched) {
            SpriteRenderer.instance.StartShader(this.shader.getID(), playerIndex);
        }

        long now = System.currentTimeMillis();
        this.drawDents(this.visiblePrints, prints, this.footprints, now);
        if (!this.visibleTires.isEmpty()) {
            this.drawRuts(tires, now);
        }

        IndieGL.glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE_MINUS_DST_ALPHA, GL_ONE);
        this.drawCarried(this.visiblePrints, prints, this.carriedFootprints, now);
        this.drawCarried(this.visibleTires, tires, this.carriedTireTracks, now);
        SpriteRenderer.instance.StartShader(0, playerIndex);
        this.visiblePrints.clear();
        this.visibleTires.clear();
    }

    // With the world's first frame, like vanilla's tile shaders, instead of with the first track, which stuttered
    private void loadResources() {
        if (!this.resourcesLoaded) {
            this.resourcesLoaded = true;
            this.footprints = Texture.getSharedTexture(FOOTPRINT_ATLAS);
            this.tireTracks = Texture.getSharedTexture(TIRE_ATLAS);
            this.carriedFootprints = Texture.getSharedTexture(CARRIED_FOOTPRINT_ATLAS);
            this.carriedTireTracks = Texture.getSharedTexture(CARRIED_TIRE_ATLAS);
            this.shader = new TrackShader();
            this.shader.getProgram(); // compiles it
        }
    }

    private void collectVisible(int playerIndex, int level, SnowTracks.Layer prints, SnowTracks.Layer tires) {
        float width = IsoCamera.frameState.offscreenWidth;
        float height = IsoCamera.frameState.offscreenHeight;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            float sx = (corner & 1) == 0 ? 0.0f : width;
            float sy = (corner & 2) == 0 ? 0.0f : height;
            float wx = IsoUtils.XToIso(playerIndex, sx, sy, level);
            float wy = IsoUtils.YToIso(playerIndex, sx, sy, level);
            minX = Math.min(minX, wx);
            minY = Math.min(minY, wy);
            maxX = Math.max(maxX, wx);
            maxY = Math.max(maxY, wy);
        }

        int chunkX0 = PZMath.fastfloor(minX / 8.0f) - 1;
        int chunkY0 = PZMath.fastfloor(minY / 8.0f) - 1;
        int chunkX1 = PZMath.fastfloor(maxX / 8.0f) + 1;
        int chunkY1 = PZMath.fastfloor(maxY / 8.0f) + 1;
        for (int chunkX = chunkX0; chunkX <= chunkX1; chunkX++) {
            for (int chunkY = chunkY0; chunkY <= chunkY1; chunkY++) {
                prints.collect(chunkX, chunkY, level, this.visiblePrints);
                tires.collect(chunkX, chunkY, level, this.visibleTires);
            }
        }
    }

    // The stencil lets only one rut darken each pixel, the newest. Its state bypasses IndieGL's cache, which is
    // restored afterwards.
    private void drawRuts(SnowTracks.Layer tires, long now) {
        this.visibleTires.sort(NEWEST_FIRST);
        SpriteRenderer.instance.glEnable(GL_STENCIL_TEST);
        SpriteRenderer.instance.glStencilMask(RUT_STENCIL_BIT);
        SpriteRenderer.instance.glStencilFunc(GL_NOTEQUAL, RUT_STENCIL_BIT, RUT_STENCIL_BIT);
        SpriteRenderer.instance.glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
        this.drawDents(this.visibleTires, tires, this.tireTracks, now);
        GLState.StencilTest.restore();
        GLState.StencilMask.restore();
        GLState.StencilFunc.restore();
        GLState.StencilOp.restore();
    }

    private void drawDents(ArrayList<SnowTracks.Track> tracks, SnowTracks.Layer layer, Texture atlas, long now) {
        if (atlas == null) {
            return;
        }

        for (int i = 0; i < tracks.size(); i++) {
            SnowTracks.Track track = tracks.get(i);
            if (!track.isCarried()) {
                float strength = DARKNESS * fade(layer.getAge(track)) * track.getReplacedFade(now);
                if (strength >= MIN_STRENGTH) {
                    strength *= this.getSeenFade(track, now);
                    if (strength >= MIN_STRENGTH) {
                        this.drawQuad(atlas, track, strength);
                    }
                }
            }
        }
    }

    // Alpha blended and lit by the square's light, like the snow overlay
    private void drawCarried(ArrayList<SnowTracks.Track> tracks, SnowTracks.Layer layer, Texture atlas, long now) {
        if (atlas == null) {
            return;
        }

        for (int i = 0; i < tracks.size(); i++) {
            SnowTracks.Track track = tracks.get(i);
            if (track.isCarried()) {
                float alpha = track.getStrength() * fade(layer.getAge(track)) * track.getReplacedFade(now);
                if (alpha >= MIN_STRENGTH) {
                    alpha *= this.getSeenFade(track, now);
                    if (alpha >= MIN_STRENGTH) {
                        this.drawQuad(atlas, track, alpha);
                    }
                }
            }
        }
    }

    // Looked up every frame, as the snow can shrink back after a dent was made
    private float getCoverage(float x, float y, int z) {
        int squareX = PZMath.fastfloor(x);
        int squareY = PZMath.fastfloor(y);
        int i = squareX * 31 + squareY * 17 + z & SIDES_CACHE - 1;
        if (this.cacheFrame[i] != this.frame || this.cacheX[i] != squareX || this.cacheY[i] != squareY || this.cacheZ[i] != z) {
            this.cacheFrame[i] = this.frame;
            this.cacheX[i] = squareX;
            this.cacheY[i] = squareY;
            this.cacheZ[i] = z;
            this.cacheSides[i] = FBORenderSnow.getInstance().getSnowSides(squareX, squareY, z);
        }

        return FBORenderSnow.getCoverage(this.cacheSides[i], x - squareX, y - squareY);
    }

    private boolean updateLight(SnowTracks.Track track) {
        IsoGridSquare square = IsoWorld.instance.currentCell.getGridSquare(PZMath.fastfloor(track.getX()), PZMath.fastfloor(track.getY()), track.getZ());
        if (square == null) {
            return false;
        }

        int r = 0, g = 0, b = 0;
        for (int i = 0; i < 4; i++) {
            int light = square.getVertLight(i, this.playerIndex);
            r += light & 0xFF;
            g += light >> 8 & 0xFF;
            b += light >> 16 & 0xFF;
        }

        this.lightR = r / 1020.0f;
        this.lightG = g / 1020.0f;
        this.lightB = b / 1020.0f;
        return true;
    }

    private static float fade(float age) {
        return age <= FULL_STRENGTH_UNTIL ? 1.0f : Math.max(0.0f, 1.0f - (age - FULL_STRENGTH_UNTIL) / (1.0f - FULL_STRENGTH_UNTIL));
    }

    private void drawQuad(Texture atlas, SnowTracks.Track track, float strength) {
        float x = track.getX();
        float y = track.getY();
        int z = track.getZ();
        float fx = track.getDirX() * track.getLength() * 0.5f;
        float fy = track.getDirY() * track.getLength() * 0.5f;
        float mirror = track.getType() == SnowTracks.FOOT_LEFT ? -1.0f : 1.0f; // the texture is a right foot
        float frontRx = track.getFrontAcrossX() * mirror;
        float frontRy = track.getFrontAcrossY() * mirror;
        float rearRx = track.getRearAcrossX() * mirror;
        float rearRy = track.getRearAcrossY() * mirror;
        this.corner(0, x + fx - frontRx, y + fy - frontRy, z);
        this.corner(1, x + fx + frontRx, y + fy + frontRy, z);
        this.corner(2, x - fx + rearRx, y - fy + rearRy, z);
        this.corner(3, x - fx - rearRx, y - fy - rearRy, z);
        if (!this.isOnScreen()) {
            return;
        }

        // Carried snow is lit by its square's light; a dent fades out with the snow under each of its corners
        if (track.isCarried()) {
            if (!this.updateLight(track)) {
                return;
            }

            int color = Color.colorToABGR(this.lightR, this.lightG, this.lightB, strength);
            for (int i = 0; i < 4; i++) {
                this.atlasQuad.colors[i] = color;
            }
        } else {
            boolean visible = false;
            for (int i = 0; i < 4; i++) {
                float darkening = strength * this.getCoverage(this.worldX[i], this.worldY[i], z);
                this.atlasQuad.colors[i] = Color.colorToABGR(darkening, darkening, darkening, 1.0f);
                visible |= darkening >= MIN_STRENGTH;
            }

            if (!visible) {
                return;
            }
        }

        if (this.batched) {
            // The floor's depth at each corner: whatever stands in front of that floor hides the track, like the floor.
            // It falls by the same amount per square everywhere, so one lookup gives all four
            float depth = this.getFloorDepth(this.worldX[0] + FLOOR_BIAS, this.worldY[0] + FLOOR_BIAS, z);
            for (int i = 0; i < 4; i++) {
                this.atlasQuad.depths[i] = depth + FLOOR_DEPTH_STEP * (this.worldX[i] - this.worldX[0] + this.worldY[i] - this.worldY[0]);
            }
        } else {
            // The default shader's depth is per batch, a shader op starts a new one. The corner nearest the viewer, so
            // the floor never hides the track
            int front = 0;
            for (int i = 1; i < 4; i++) {
                if (this.worldX[i] + this.worldY[i] > this.worldX[front] + this.worldY[front]) {
                    front = i;
                }
            }

            SpriteRenderer.instance.StartShader(0, this.playerIndex);
            TextureDraw.nextZ = this.getFloorDepth(this.worldX[front] + DEPTH_BIAS, this.worldY[front] + DEPTH_BIAS, z);
        }

        this.atlasQuad.set(atlas, track.getVariant());
        SpriteRenderer.instance.render(
                atlas,
                this.screenX[0],
                this.screenY[0],
                this.screenX[1],
                this.screenY[1],
                this.screenX[2],
                this.screenY[2],
                this.screenX[3],
                this.screenY[3],
                1.0f,
                1.0f,
                1.0f,
                1.0f,
                this.atlasQuad
        );
    }

    private float getFloorDepth(float x, float y, int z) {
        return IsoDepthHelper.getSquareDepthData(
                PZMath.fastfloor(IsoCamera.frameState.camCharacterX),
                PZMath.fastfloor(IsoCamera.frameState.camCharacterY), x + this.jiggleX, y + this.jiggleY, z).depthStart * 2.0f - 1.0f;
    }

    private boolean isOnScreen() {
        float minX = Math.min(Math.min(this.screenX[0], this.screenX[1]), Math.min(this.screenX[2], this.screenX[3]));
        float maxX = Math.max(Math.max(this.screenX[0], this.screenX[1]), Math.max(this.screenX[2], this.screenX[3]));
        float minY = Math.min(Math.min(this.screenY[0], this.screenY[1]), Math.min(this.screenY[2], this.screenY[3]));
        float maxY = Math.max(Math.max(this.screenY[0], this.screenY[1]), Math.max(this.screenY[2], this.screenY[3]));
        return maxX >= 0.0f && minX <= IsoCamera.frameState.offscreenWidth && maxY >= 0.0f && minY <= IsoCamera.frameState.offscreenHeight;
    }

    private void corner(int i, float x, float y, int z) {
        this.worldX[i] = x;
        this.worldY[i] = y;
        this.screenX[i] = IsoUtils.XToScreen(x + this.jiggleX, y + this.jiggleY, z, 0) - IsoCamera.frameState.offX;
        this.screenY[i] = IsoUtils.YToScreen(x + this.jiggleX, y + this.jiggleY, z, 0) - IsoCamera.frameState.offY;
    }

    // Tracks show once the player has had them in view, so prints made out of sight don't give away who made them.
    // They fade in when they come into view; ones made in view show at once
    private float getSeenFade(SnowTracks.Track track, long now) {
        if (!track.isSeenBy(this.playerIndex)) {
            IsoGridSquare square = IsoWorld.instance.getCell().getGridSquare(PZMath.fastfloor(track.getX()), PZMath.fastfloor(track.getY()), track.getZ());
            if (square == null || !square.isCanSee(this.playerIndex)) {
                return 0.0f;
            }

            boolean madeInView = now - track.getCreatedMs() < SEEN_FADE_MS;
            track.markSeenBy(this.playerIndex, madeInView ? now - SEEN_FADE_MS : now);
        }

        return Math.min(1.0f, (float) (now - track.getSeenAt()) / SEEN_FADE_MS);
    }

    // Picks a variant out of an atlas, sets the corners' colours, and puts their depths in tex2's UVs for the shader
    private static final class AtlasQuad implements Consumer<TextureDraw> {
        private final int[] colors = new int[4];
        private final float[] depths = new float[4];
        private Texture atlas;
        private int variant;

        private void set(Texture atlas, int variant) {
            this.atlas = atlas;
            this.variant = variant;
        }

        @Override
        public void accept(TextureDraw texd) {
            float scale = (this.atlas.getXEnd() - this.atlas.getXStart()) / this.atlas.getWidth();
            float cell = this.atlas.getWidth() / (float) SnowTracks.VARIANTS;
            float left = this.atlas.getXStart() + (this.variant * cell + ATLAS_GUTTER) * scale;
            float right = this.atlas.getXStart() + ((this.variant + 1) * cell - ATLAS_GUTTER) * scale;
            float top = this.atlas.getYStart();
            float bottom = this.atlas.getYEnd();
            texd.u0 = left;
            texd.v0 = top;
            texd.u1 = right;
            texd.v1 = top;
            texd.u2 = right;
            texd.v2 = bottom;
            texd.u3 = left;
            texd.v3 = bottom;
            texd.col0 = this.colors[0];
            texd.col1 = this.colors[1];
            texd.col2 = this.colors[2];
            texd.col3 = this.colors[3];
            texd.tex2 = this.atlas;
            texd.tex2U0 = this.depths[0];
            texd.tex2U1 = this.depths[1];
            texd.tex2U2 = this.depths[2];
            texd.tex2U3 = this.depths[3];
        }
    }

    private static final class TrackShader extends Shader {
        private TrackShader() {
            super("snowTracks");
        }

        @Override
        public void startRenderThread(TextureDraw tex) {
            super.startRenderThread(tex);
            VertexBufferObject.setModelViewProjection(this.getProgram());
        }

        @Override
        protected void onCompileSuccess(ShaderProgram shaderProgram) {
            this.Start();
            shaderProgram.setSamplerUnit("DIFFUSE", 0);
            this.End();
        }
    }
}
