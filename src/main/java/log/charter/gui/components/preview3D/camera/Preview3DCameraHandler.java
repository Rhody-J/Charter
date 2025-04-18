package log.charter.gui.components.preview3D.camera;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.pow;
import static log.charter.gui.components.preview3D.Preview3DUtils.getFretPosition;
import static log.charter.gui.components.preview3D.Preview3DUtils.topStringPosition;
import static log.charter.gui.components.preview3D.glUtils.Matrix4.cameraMatrix;
import static log.charter.gui.components.preview3D.glUtils.Matrix4.moveMatrix;
import static log.charter.gui.components.preview3D.glUtils.Matrix4.rotationXMatrix;
import static log.charter.gui.components.preview3D.glUtils.Matrix4.rotationYMatrix;
import static log.charter.gui.components.preview3D.glUtils.Matrix4.scaleMatrix;
import static log.charter.util.CollectionUtils.lastBeforeEqual;

import java.util.List;
import java.util.Random;

import log.charter.data.ChartData;
import log.charter.data.config.values.InstrumentConfig;
import log.charter.data.config.values.SecretsConfig;
import log.charter.data.song.FHP;
import log.charter.data.song.position.fractional.IConstantFractionalPosition;
import log.charter.data.song.position.time.Position;
import log.charter.gui.components.preview3D.glUtils.Matrix4;
import log.charter.services.data.ChartTimeHandler;

public class Preview3DCameraHandler {

	private static final Matrix4 baseCameraPerspectiveMatrix = cameraMatrix(-0.3, -0.3, -0.3, -1)//
			.multiply(scaleMatrix(1, 1, -1));

	private final static int fretFocusWindowStartOffset = 0;
	private final static int fretFocusWindowEndOffset = 3000;
	private static final double focusingSpeed = 0.7;

	private final static double minScreenScaleX = 0.5;
	private final static double screenScaleXMultiplier = 1;
	private final static double minScreenScaleY = 1;
	private final static double screenScaleYMultiplier = 0.5;

	private static final double weightedPosition = getFretPosition(InstrumentConfig.frets) * 0.4
			+ getFretPosition(0) * 0.6;
	private static final double weightedPositionWeight = 0.1;

	private final Random random = new Random();

	private double getRandomShakeValue(final double multiplier) {
		return (random.nextDouble() * 2 - 1) * multiplier;
	}

	private class CameraFinalData {
		final double camY;
		final double camZ;
		final double camRotationX;
		final double camRotationY;

		final double screenScaleX;
		final double screenScaleY;

		public CameraFinalData(final double aspectRatio) {
			camY = 1.3 + topStringPosition + (fretSpan - 4) * 0.2;
			camZ = -2.5 + (fretSpan - 4) * -0.2;
			camRotationX = 0.2 + Math.sqrt(fretSpan - 4) * 0.01;
			camRotationY = 0.06;

			screenScaleX = min(minScreenScaleX, screenScaleXMultiplier / aspectRatio);
			screenScaleY = min(minScreenScaleY, screenScaleYMultiplier * aspectRatio);
		}

		public CameraFinalData(final double aspectRatio, final double shake) {
			camY = 1.3 + topStringPosition + (fretSpan - 4) * 0.2 + getRandomShakeValue(shake * 0.5);
			camZ = -2.5 + (fretSpan - 4) * -0.2 + getRandomShakeValue(shake * 0.5);
			camRotationX = 0.2 + Math.sqrt(fretSpan - 4) * 0.01 + getRandomShakeValue(shake * 0.1);
			camRotationY = 0.06 + getRandomShakeValue(shake * 0.1);

			screenScaleX = min(minScreenScaleX, screenScaleXMultiplier / aspectRatio);
			screenScaleY = min(minScreenScaleY, screenScaleYMultiplier * aspectRatio);
		}

		public Matrix4 generateMatrix() {
			return scaleMatrix(screenScaleX, screenScaleY, 1 / 10.0)//
					.multiply(baseCameraPerspectiveMatrix)//
					.multiply(rotationXMatrix(camRotationX))//
					.multiply(rotationYMatrix(camRotationY))//
					.multiply(moveMatrix(-camX, -camY, -camZ));
		}
	}

	private ChartTimeHandler chartTimeHandler;
	private ChartData chartData;

	private double camX = 2.5;
	private double fretSpan = 4;
	private long cameraShakeTime = 0;
	private int cameraShakeStrength = 0;

	public Matrix4 currentMatrix;

	public void init(final ChartTimeHandler chartTimeHandler, final ChartData data) {
		this.chartTimeHandler = chartTimeHandler;
		chartData = data;
	}

	private double mix(final double a, final double b, final double mix) {
		return a * mix + b * (1 - mix);
	}

	public void updateFretFocus(final double frameTime) {
		final List<FHP> fhps = chartData.currentArrangementLevel().fhps;
		int minFret = InstrumentConfig.frets;
		int maxFret = 1;

		final IConstantFractionalPosition start = new Position(chartTimeHandler.time() + fretFocusWindowStartOffset)
				.toFraction(chartData.beats());
		final int fhpsFrom = lastBeforeEqual(fhps, start).findId(0);

		final IConstantFractionalPosition end = new Position(chartTimeHandler.time() + fretFocusWindowEndOffset)
				.toFraction(chartData.beats());
		final Integer fhpsTo = lastBeforeEqual(fhps, end).findId();
		if (fhpsTo == null) {
			return;
		}
		// TODO add weighted average instead of focusing speed

		for (int i = fhpsFrom; i <= fhpsTo; i++) {
			final FHP fhp = fhps.get(i);
			if (fhp.fret < minFret) {
				minFret = fhp.fret;
			}
			if (fhp.topFret() > maxFret) {
				maxFret = fhp.topFret();
			}
		}

		final double focusSpeed = 1 - pow(1 - focusingSpeed, frameTime);
		final double middleFHPPosition = (getFretPosition(maxFret) + getFretPosition(minFret - 1)) / 2;
		final double targetCamX = 1 + middleFHPPosition * (1 - weightedPositionWeight)
				+ weightedPosition * weightedPositionWeight;
		camX = mix(targetCamX, camX, focusSpeed);
		final double targetFretSpan = (maxFret - minFret + 1);
		fretSpan = mix(targetFretSpan, fretSpan, focusSpeed);
	}

	public void shakeCamera(final int strength) {
		cameraShakeTime = System.nanoTime();
		cameraShakeStrength = strength;
	}

	public void updateCamera(final double aspectRatio) {
		final CameraFinalData cameraFinalData;
		if (SecretsConfig.explosionsShakyCamEnabled()) {
			double shake = max(0, 1 - (System.nanoTime() - cameraShakeTime) / 1_000_000_000.0);
			shake = pow(shake, 3) * cameraShakeStrength / chartData.currentStrings();
			cameraFinalData = new CameraFinalData(aspectRatio, shake);
		} else {
			cameraFinalData = new CameraFinalData(aspectRatio);
		}

		currentMatrix = cameraFinalData.generateMatrix();
	}
}
