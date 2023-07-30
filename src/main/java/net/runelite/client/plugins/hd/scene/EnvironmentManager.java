/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.hd.scene;

import com.google.common.primitives.Floats;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import static net.runelite.api.Constants.CHUNK_SIZE;
import net.runelite.api.GameState;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.hd.HdPlugin;
import net.runelite.client.plugins.hd.HdPluginConfig;
import net.runelite.client.plugins.hd.data.environments.Environment;
import net.runelite.client.plugins.hd.utils.HDUtils;
import net.runelite.client.plugins.hd.config.DefaultSkyColor;
import net.runelite.client.plugins.hd.utils.Rect;

@Singleton
@Slf4j
public class EnvironmentManager
{

	private ExecutorService executorService = Executors.newSingleThreadExecutor();

	@Inject
	private Client client;

	@Inject
	private HdPluginConfig config;

	@Inject
	private HdPlugin hdPlugin;

	private ArrayList<Environment> sceneEnvironments;
	private Environment currentEnvironment;
	private final Environment defaultEnvironment = Environment.OVERWORLD;

	// transition time
	private final int transitionDuration = 3000;
	// distance in tiles to skip transition (e.g. entering cave, teleporting)
	// walking across a loading line causes a movement of 40-41 tiles
	private final int skipTransitionTiles = 41;

	// last environment change time
	private long startTime = 0;
	// transition complete time
	private long transitionCompleteTime = 0;
	// time of last frame; used for lightning
	long lastFrameTime = -1;
	// used for tracking changes to settings
	DefaultSkyColor lastSkyColor = DefaultSkyColor.DEFAULT;
	boolean lastEnvironmentLighting = true;
	boolean lastSkyOverride = false;
	boolean lastUnderwater = false;

	// previous camera target world X
	private int prevCamTargetX = 0;
	// previous camera target world Y
	private int prevCamTargetY = 0;

	public static final float[] BLACK_COLOR = {0,0,0};

	private float[] startFogColor = new float[]{0,0,0};
	public float[] currentFogColor = new float[]{0,0,0};
	private float[] targetFogColor = new float[]{0,0,0};

	private float[] startWaterColor = new float[]{0,0,0};
	public float[] currentWaterColor = new float[]{0,0,0};
	private float[] targetWaterColor = new float[]{0,0,0};


	private float[] blackFogColor = new float[]{0, 0, 0};
	private int startFogDepth = 0;
	public int currentFogDepth = 0;
	private int targetFogDepth = 0;

	private float startAmbientStrength = 0f;
	public float currentAmbientStrength = 0f;
	private float targetAmbientStrength = 0f;

	private float[] startAmbientColor = new float[]{0,0,0};
	public float[] currentAmbientColor = new float[]{0,0,0};
	private float[] targetAmbientColor = new float[]{0,0,0};

	private float startDirectionalStrength = 0f;
	public float currentDirectionalStrength = 0f;
	private float targetDirectionalStrength = 0f;

	private float[] startUnderwaterCausticsColor = new float[]{0,0,0};
	public float[] currentUnderwaterCausticsColor = new float[]{0,0,0};
	private float[] targetUnderwaterCausticsColor = new float[]{0,0,0};

	private float startUnderwaterCausticsStrength = 1f;
	public float currentUnderwaterCausticsStrength = 1f;
	private float targetUnderwaterCausticsStrength = 1f;

	private float[] startDirectionalColor = new float[]{0,0,0};
	public float[] currentDirectionalColor = new float[]{0,0,0};
	private float[] targetDirectionalColor = new float[]{0,0,0};

	private float startUnderglowStrength = 0f;
	public float currentUnderglowStrength = 0f;
	private float targetUnderglowStrength = 0f;

	private float[] startUnderglowColor = new float[]{0,0,0};
	public float[] currentUnderglowColor = new float[]{0,0,0};
	private float[] targetUnderglowColor = new float[]{0,0,0};

	private float startGroundFogStart = 0f;
	public float currentGroundFogStart = 0f;
	private float targetGroundFogStart = 0f;

	private float startGroundFogEnd = 0f;
	public float currentGroundFogEnd = 0f;
	private float targetGroundFogEnd = 0f;

	private float startGroundFogOpacity = 0f;
	public float currentGroundFogOpacity = 0f;
	private float targetGroundFogOpacity = 0f;

	private float startLightPitch = 0f;
	public float currentLightPitch = 0f;
	private float targetLightPitch = 0f;

	private float startLightYaw = 0f;
	public float currentLightYaw = 0f;
	private float targetLightYaw = 0f;

	public boolean lightningEnabled = false;

	private float cubicInterpolate(float y0, float y1, float y2, float y3, float t) {
		float a0, a1, a2, a3;
		float t2 = t * t;
		a0 = y3 - y2 - y0 + y1;
		a1 = y0 - y1 - a0;
		a2 = y2 - y0;
		a3 = y1;
		return a0 * t * t2 + a1 * t2 + a2 * t + a3;
	}

	public void update() {
		WorldPoint camPosition = localPointToWorldTile(hdPlugin.camTarget[0], hdPlugin.camTarget[1]);
		int camTargetX = camPosition.getX();
		int camTargetY = camPosition.getY();
		int camTargetZ = camPosition.getPlane();

		for (Environment environment : sceneEnvironments) {
			if (environment.getArea().containsPoint(camTargetX, camTargetY, camTargetZ)) {
				if (environment != currentEnvironment) {
					if (environment == Environment.PLAYER_OWNED_HOUSE || environment == Environment.PLAYER_OWNED_HOUSE_SNOWY) {
						hdPlugin.setInHouse(true);
						hdPlugin.setNextSceneReload(System.currentTimeMillis() + 2500);
					} else {
						hdPlugin.setInHouse(false);
					}

					hdPlugin.setInGauntlet(environment == Environment.THE_GAUNTLET || environment == Environment.THE_GAUNTLET_CORRUPTED);

					changeEnvironment(environment, camTargetX, camTargetY, false);
				}
				break;
			}
		}

		if (lastSkyColor != config.defaultSkyColor() ||
				lastEnvironmentLighting != config.atmosphericLighting() ||
				lastSkyOverride != config.overrideSky() ||
				lastUnderwater != isUnderwater()) {
			changeEnvironment(currentEnvironment, camTargetX, camTargetY, true);
		}

		// Perform cubic interpolation for each environmental parameter
		long currentTime = System.currentTimeMillis();
		float t = (float) (currentTime - startTime) / (float) transitionDuration;
		t = Math.max(0.0f, Math.min(1.0f, t)); // Clamp t between 0 and 1

		currentFogColor = HDUtils.lerpVectors(startFogColor, targetFogColor, cubicInterpolate(0, 0, 1, 1, t));
		currentWaterColor = HDUtils.lerpVectors(startWaterColor, targetWaterColor, cubicInterpolate(0, 0, 1, 1, t));
		currentFogDepth = (int) HDUtils.lerp(startFogDepth, targetFogDepth, cubicInterpolate(0, 0, 1, 1, t));
		currentAmbientStrength = HDUtils.lerp(startAmbientStrength, targetAmbientStrength, cubicInterpolate(0, 0, 1, 1, t));
		currentAmbientColor = HDUtils.lerpVectors(startAmbientColor, targetAmbientColor, cubicInterpolate(0, 0, 1, 1, t));
		currentDirectionalStrength = HDUtils.lerp(startDirectionalStrength, targetDirectionalStrength, cubicInterpolate(0, 0, 1, 1, t));
		currentDirectionalColor = HDUtils.lerpVectors(startDirectionalColor, targetDirectionalColor, cubicInterpolate(0, 0, 1, 1, t));
		currentUnderglowStrength = HDUtils.lerp(startUnderglowStrength, targetUnderglowStrength, cubicInterpolate(0, 0, 1, 1, t));
		currentUnderglowColor = HDUtils.lerpVectors(startUnderglowColor, targetUnderglowColor, cubicInterpolate(0, 0, 1, 1, t));
		currentGroundFogStart = HDUtils.lerp(startGroundFogStart, targetGroundFogStart, cubicInterpolate(0, 0, 1, 1, t));
		currentGroundFogEnd = HDUtils.lerp(startGroundFogEnd, targetGroundFogEnd, cubicInterpolate(0, 0, 1, 1, t));
		currentGroundFogOpacity = HDUtils.lerp(startGroundFogOpacity, targetGroundFogOpacity, cubicInterpolate(0, 0, 1, 1, t));
		currentLightPitch = HDUtils.lerp(startLightPitch, targetLightPitch, cubicInterpolate(0, 0, 1, 1, t));
		currentLightYaw = HDUtils.lerp(startLightYaw, targetLightYaw, cubicInterpolate(0, 0, 1, 1, t));
		currentUnderwaterCausticsColor = HDUtils.lerpVectors(startUnderwaterCausticsColor, targetUnderwaterCausticsColor, cubicInterpolate(0, 0, 1, 1, t));
		currentUnderwaterCausticsStrength = HDUtils.lerp(startUnderwaterCausticsStrength, targetUnderwaterCausticsStrength, cubicInterpolate(0, 0, 1, 1, t));

		// Update the lightning effects
		updateLightning();

		// Update some variables for use in the next frame
		prevCamTargetX = camTargetX;
		prevCamTargetY = camTargetY;
		lastFrameTime = System.currentTimeMillis();
		lastSkyColor = config.defaultSkyColor();
		lastSkyOverride = config.overrideSky();
		lastEnvironmentLighting = config.atmosphericLighting();
		lastUnderwater = isUnderwater();
	}

	/**
	 * Updates variables used in transition effects
	 *
	 * @param newEnvironment
	 * @param camTargetX
	 * @param camTargetY
	 */
	private void changeEnvironment(Environment newEnvironment, int camTargetX, int camTargetY, boolean instantChange) {
		currentEnvironment = newEnvironment;
		log.debug("currentEnvironment changed to " + newEnvironment);

		startTime = System.currentTimeMillis();
		transitionCompleteTime = instantChange ? 0 : startTime + transitionDuration;

		// Set previous variables to current ones
		startFogColor = currentFogColor;
		startWaterColor = currentWaterColor;
		startFogDepth = currentFogDepth;
		startAmbientStrength = currentAmbientStrength;
		startAmbientColor = currentAmbientColor;
		startDirectionalStrength = currentDirectionalStrength;
		startDirectionalColor = currentDirectionalColor;
		startUnderglowStrength = currentUnderglowStrength;
		startUnderglowColor = currentUnderglowColor;
		startGroundFogStart = currentGroundFogStart;
		startGroundFogEnd = currentGroundFogEnd;
		startGroundFogOpacity = currentGroundFogOpacity;
		startLightPitch = currentLightPitch;
		startLightYaw = currentLightYaw;
		startUnderwaterCausticsColor = currentUnderwaterCausticsColor;
		startUnderwaterCausticsStrength = currentUnderwaterCausticsStrength;

		updateSkyColor();

		targetFogDepth = newEnvironment.getFogDepth();
		if (hdPlugin.configWinterTheme && !newEnvironment.isCustomFogDepth()) {
			targetFogDepth = Environment.WINTER.getFogDepth();
		}

		targetAmbientStrength = config.atmosphericLighting() ? newEnvironment.getAmbientStrength() : defaultEnvironment.getAmbientStrength();
		targetAmbientColor = config.atmosphericLighting() ? newEnvironment.getAmbientColor() : defaultEnvironment.getAmbientColor();
		targetDirectionalStrength = config.atmosphericLighting() ? newEnvironment.getDirectionalStrength() : defaultEnvironment.getDirectionalStrength();
		targetDirectionalColor = config.atmosphericLighting() ? newEnvironment.getDirectionalColor() : defaultEnvironment.getDirectionalColor();
		targetUnderglowStrength = config.atmosphericLighting() ? newEnvironment.getUnderglowStrength() : defaultEnvironment.getUnderglowStrength();
		targetUnderglowColor = config.atmosphericLighting() ? newEnvironment.getUnderglowColor() : defaultEnvironment.getUnderglowColor();
		targetLightPitch = config.atmosphericLighting() ? newEnvironment.getLightPitch() : defaultEnvironment.getLightPitch();
		targetLightYaw = config.atmosphericLighting() ? newEnvironment.getLightYaw() : defaultEnvironment.getLightYaw();

		if (hdPlugin.configWinterTheme) {
			if (!config.atmosphericLighting()) {
				if (!defaultEnvironment.isCustomAmbientStrength()) {
					targetAmbientStrength = Environment.WINTER.getAmbientStrength();
				}
				if (!defaultEnvironment.isCustomAmbientColor()) {
					targetAmbientColor = Environment.WINTER.getAmbientColor();
				}
				if (!defaultEnvironment.isCustomDirectionalStrength()) {
					targetDirectionalStrength = Environment.WINTER.getDirectionalStrength();
				}
				if (!defaultEnvironment.isCustomDirectionalColor()) {
					targetDirectionalColor = Environment.WINTER.getDirectionalColor();
				}
			}
		}

		targetGroundFogStart = newEnvironment.getGroundFogStart();
		targetGroundFogEnd = newEnvironment.getGroundFogEnd();
		targetGroundFogOpacity = newEnvironment.getGroundFogOpacity();
		targetUnderwaterCausticsColor = newEnvironment.getUnderwaterCausticsColor();
		targetUnderwaterCausticsStrength = newEnvironment.getUnderwaterCausticsStrength();

		lightningEnabled = newEnvironment.isLightningEnabled();

		int tileChangeX = Math.abs(prevCamTargetX - camTargetX);
		int tileChangeY = Math.abs(prevCamTargetY - camTargetY);
		int tileChange = Math.max(tileChangeX, tileChangeY);

		// Skip the transitional fade if the player has moved too far
		// since the previous frame. Results in an instant transition when
		// teleporting, entering dungeons, etc.
		if (tileChange >= skipTransitionTiles) {
			transitionCompleteTime = startTime;
		}
	}

	public void updateSkyColor()
	{
		Environment env = hdPlugin.configWinterTheme ? Environment.WINTER : currentEnvironment;
		DefaultSkyColor sky = config.defaultSkyColor();
		if (!env.isCustomFogColor() || (env.isAllowSkyOverride() && config.overrideSky()))
		{
			targetFogColor = sky.getRgb(client);
			if (sky == DefaultSkyColor.OSRS)
			{
				sky = DefaultSkyColor.DEFAULT;
			}
			targetWaterColor = sky.getRgb(client);
		}
		else
		{
			targetFogColor = targetWaterColor = env.getFogColor();
		}


		// Override with decoupled water/sky color if present
		if (currentEnvironment.isCustomWaterColor())
		{
			targetWaterColor = currentEnvironment.getWaterColor();
		}
	}


	public void loadSceneEnvironmentsAsync()
	{
		// You can perform any post-loading tasks here if needed.
		executorService.execute(this::loadSceneEnvironments);
	}

	public void shutdownExecutorService()
	{
		executorService.shutdown();
	}



	/**
	 * Figures out which Areas exist in the current scene and
	 * adds them to lists for easy access.
	 */
	public void loadSceneEnvironments()
	{
		// loop through all Areas, check Rects of each Area. if any
		// coordinates overlap scene coordinates, add them to a list.
		// then loop through all Environments, checking to see if any
		// of their Areas match any of the ones in the current scene.
		// if so, add them to a list.

		sceneEnvironments = new ArrayList<>();

		int sceneMinX = client.getBaseX();
		int sceneMinY = client.getBaseY();
		if (client.isInInstancedRegion())
		{
			LocalPoint localPoint = client.getLocalPlayer() != null ? client.getLocalPlayer().getLocalLocation() : new LocalPoint(0, 0);
			WorldPoint worldPoint = WorldPoint.fromLocalInstance(client, localPoint);
			sceneMinX = worldPoint.getX() - localPoint.getSceneX();
			sceneMinY = worldPoint.getY() - localPoint.getSceneY();
		}
		int sceneMaxX = sceneMinX + Constants.SCENE_SIZE - 2;
		int sceneMaxY = sceneMinY + Constants.SCENE_SIZE - 2;

		log.debug("adding environments for scene {},{} - {},{}..", sceneMinX, sceneMinY, sceneMaxX, sceneMaxY);

		for (Environment environment: Environment.values())
		{
			for (Rect rect : environment.getArea().getRects())
			{
				if (rect.getMinX() > sceneMaxX || sceneMinX > rect.getMaxX() || rect.getMinY() > sceneMaxY || sceneMinY > rect.getMaxY())
				{
					continue;
				}
				log.debug("added environment {} to sceneArea list", environment.name());
				sceneEnvironments.add(environment);
				break;
			}
		}

		for (Environment environment : sceneEnvironments)
		{
			log.debug("sceneArea: " + environment.name());
		}

		if (currentEnvironment != null)
		{
			WorldPoint camPosition = localPointToWorldTile(hdPlugin.camTarget[0], hdPlugin.camTarget[1]);
			int camTargetX = camPosition.getX();
			int camTargetY = camPosition.getY();
			changeEnvironment(currentEnvironment, camTargetX, camTargetY, false);
		}
	}



	/* lightning */
	public float lightningBrightness = 0f;
	public float[] lightningColor = new float[]{1.0f, 1.0f, 1.0f};
	double nextLightningTime = -1;
	float newLightningBrightness = 7f;
	float lightningFadeSpeed = 80f; // brightness units per second
	int minLightningInterval = 5500;
	int maxLightningInterval = 17000;
	float quickLightningChance = 2f;
	int minQuickLightningInterval = 40;
	int maxQuickLightningInterval = 150;

	/**
	 * Updates lightning variables and sets water reflection and sky
	 * colors during lightning flashes.
	 */
	void updateLightning()
	{
		if (lightningBrightness > 0)
		{
			int frameTime = (int)(System.currentTimeMillis() - lastFrameTime);
			float brightnessChange = (frameTime / 1000f) * lightningFadeSpeed;
			lightningBrightness = Math.max(lightningBrightness - brightnessChange, 0);
		}

		if (nextLightningTime == -1)
		{
			generateNextLightningTime();
			return;
		}
		if (System.currentTimeMillis() > nextLightningTime)
		{
			lightningBrightness = newLightningBrightness;

			generateNextLightningTime();
		}

		if (lightningEnabled && config.flashingEffects())
		{
			float t = Floats.constrainToRange(lightningBrightness, 0.0f, 1.0f);
			currentFogColor = HDUtils.lerpVectors(currentFogColor, lightningColor, t);
			currentWaterColor = HDUtils.lerpVectors(currentWaterColor, lightningColor, t);
		}
		else
		{
			lightningBrightness = 0f;
		}
	}

	/**
	 * Determines when the next lighting strike will occur.
	 * Produces a short interval for a quick successive strike
	 * or a longer interval at the end of a cluster.
	 */
	void generateNextLightningTime()
	{
		int lightningInterval = (int) (minLightningInterval + ((maxLightningInterval - minLightningInterval) * Math.random()));
		int quickLightningInterval = (int) (minQuickLightningInterval + ((maxQuickLightningInterval - minQuickLightningInterval) * Math.random()));
		nextLightningTime = System.currentTimeMillis() + (Math.random() <= 1f / quickLightningChance ? quickLightningInterval : lightningInterval);
	}

	/**
	 * Returns the current fog color if logged in.
	 * Else, returns solid black.
	 *
	 * @return
	 */
	public float[] getFogColor()
	{
		return client.getGameState().getState() >= GameState.LOADING.getState() ?
				Arrays.copyOf(currentFogColor, 3) : blackFogColor;
	}

	/**
	 * Returns the world tile coordinates of a given local point, adjusted to template coordinates if within an instance.
	 *
	 * @param pointX
	 * @param pointY
	 * @return adjusted world coordinates
	 */
	WorldPoint localPointToWorldTile(int pointX, int pointY)
	{
		int[][][] instanceTemplateChunks = client.getInstanceTemplateChunks();
		LocalPoint localPoint = new LocalPoint(pointX, pointY);
		int chunkX = localPoint.getSceneX() / CHUNK_SIZE;
		int chunkY = localPoint.getSceneY() / CHUNK_SIZE;

		if (client.isInInstancedRegion() && chunkX >= 0 && chunkX < instanceTemplateChunks[client.getPlane()].length && chunkY >= 0 && chunkY < instanceTemplateChunks[client.getPlane()][chunkX].length)
		{
			// In some scenarios, taking the detached camera outside of instances
			// will result in a crash if we don't check the chunk array indices first
			return WorldPoint.fromLocalInstance(client, localPoint);
		}
		else
		{
			return WorldPoint.fromLocal(client, localPoint);
		}
	}

	public boolean isUnderwater()
	{
		return currentEnvironment != null && currentEnvironment.isUnderwater();
	}
}
