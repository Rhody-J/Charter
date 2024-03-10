package log.charter.gui.chartPanelDrawers.common.waveform;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static log.charter.gui.chartPanelDrawers.common.DrawerUtils.lanesBottom;
import static log.charter.gui.chartPanelDrawers.common.DrawerUtils.lanesTop;
import static log.charter.gui.chartPanelDrawers.common.waveform.WaveformMap.getSpanForLevel;
import static log.charter.util.ScalingUtils.pixelTimeLength;
import static log.charter.util.ScalingUtils.timeToX;
import static log.charter.util.ScalingUtils.xToTime;

import java.awt.Color;
import java.awt.Graphics;
import java.util.List;

import log.charter.data.config.Zoom;
import log.charter.gui.ChartPanel;
import log.charter.gui.ChartPanelColors;
import log.charter.gui.components.toolbar.ChartToolbar;
import log.charter.services.data.ProjectAudioHandler;
import log.charter.services.editModes.EditMode;
import log.charter.services.editModes.ModeManager;
import log.charter.sound.data.AudioDataInt;
import log.charter.util.CollectionUtils.Pair;

public class WaveFormDrawer {
	private static final Color normalColor = ChartPanelColors.ColorLabel.WAVEFORM_COLOR.color();
	private static final Color highIntensityColor = ChartPanelColors.ColorLabel.WAVEFORM_RMS_COLOR.color();
	private static final Color normalColorZoomed = ChartPanelColors.ColorLabel.WAVEFORM_COLOR.colorWithAlpha(32);
	private static final Color highIntensityColorZoomed = ChartPanelColors.ColorLabel.WAVEFORM_RMS_COLOR
			.colorWithAlpha(64);

	private static int getMiddle() {
		return (lanesBottom + lanesTop) / 2;
	}

	private static int getHeight() {
		return (lanesBottom - lanesTop) / 2;
	}

	private ChartPanel chartPanel;
	private ChartToolbar chartToolbar;
	private ModeManager modeManager;
	private ProjectAudioHandler projectAudioHandler;

	private WaveformMap map = null;
	private boolean drawWaveForm;
	private boolean showRMS;

	public void toggle() {
		drawWaveForm = !drawWaveForm;
		chartToolbar.updateValues();
	}

	public void recalculateMap() {
		map = null;
		new Thread(() -> map = new WaveformMap(projectAudioHandler.getAudio())).start();
	}

	public void toggleRMS() { //
		showRMS = !showRMS;
		chartToolbar.updateValues();
	}

	public boolean rms() { //
		return showRMS;
	}

	public boolean drawing() {
		return drawWaveForm || modeManager.getMode() == EditMode.TEMPO_MAP;
	}

	private void drawFromMap(final Graphics g, final int time) {
		final WaveformMap map = this.map;
		if (map == null) {
			return;
		}

		final Pair<Integer, List<WaveformInformation>> level = map.getLevel(pixelTimeLength());
		final int timeSpan = getSpanForLevel(level.a);

		final int width = chartPanel.getWidth();
		final int y = getMiddle();
		final int fullHeight = getHeight();
		final int start = max(0, xToTime(0, time) / timeSpan);
		final int end = min(level.b.size() - 1, xToTime(chartPanel.getWidth(), time) / timeSpan);

		final int[] heights = new int[width];
		final boolean[] rms = new boolean[width];

		for (int i = start; i <= end; i++) {
			final WaveformInformation information = level.b.get(i);
			final int x = timeToX(i * timeSpan, time);
			if (x < 0 || x >= width) {
				continue;
			}

			final int height = (int) (information.height * fullHeight);
			heights[x] = Math.max(heights[x], height);
			rms[x] |= showRMS && information.rms;
		}

		final int xStart = max(0, timeToX(0, time));
		final int xEnd = min(width - 1, timeToX(projectAudioHandler.getAudio().msLength(), time));
		for (int x = xStart; x <= xEnd; x++) {
			g.setColor(rms[x] ? highIntensityColor : normalColor);
			final int height = heights[x];

			if (height > 0) {
				g.fillRect(x, y - height, 1, 2 * height + 1);
			} else {
				g.fillRect(x, y, 1, 1);
			}
		}
	}

	private void drawFull(final Graphics g, final int time) {
		final float timeToFrameMultiplier = projectAudioHandler.getAudio().frameRate() / 1000;

		final short[] musicValues = projectAudioHandler.getAudio().data[0];
		int start = (int) (xToTime(0, time) * timeToFrameMultiplier);
		start = max(1, start);
		int end = (int) (xToTime(chartPanel.getWidth(), time) * timeToFrameMultiplier);
		end = min(musicValues.length, end);

		final int midY = getMiddle();
		final int yScale = getHeight();
		int x0 = 0;
		int x1 = timeToX((int) ((start - 1) / timeToFrameMultiplier), time);
		int y0 = 0;
		int y1 = musicValues[start - 1] * yScale / 0x8000;
		final RMSCalculator rmsCalculator = new RMSCalculator((int) timeToFrameMultiplier);

		for (int frame = start; frame < end; frame++) {
			x0 = x1;
			x1 = timeToX(frame / timeToFrameMultiplier, time);
			y0 = y1;
			y1 = musicValues[frame] * yScale / 0x8000;

			rmsCalculator.addValue((float) musicValues[frame] / AudioDataInt.maxValue);

			g.setColor(rmsCalculator.getRMS() > 4 ? highIntensityColorZoomed : normalColorZoomed);
			g.drawLine(x0, midY + y0, x1, midY + y1);
		}
	}

	public void draw(final Graphics g, final int time) {
		if (!drawing()) {
			return;
		}

		if (Zoom.zoom > 1) {
			drawFull(g, time);
		} else {
			drawFromMap(g, time);
		}
	}
}
