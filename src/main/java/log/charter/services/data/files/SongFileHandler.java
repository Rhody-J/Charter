package log.charter.services.data.files;

import static log.charter.gui.components.utils.ComponentUtils.askYesNoCancel;
import static log.charter.io.rs.xml.vocals.VocalsXStreamHandler.saveVocals;
import static log.charter.io.rsc.xml.ChartProjectXStreamHandler.writeChartProject;
import static log.charter.util.FileChooseUtils.chooseFile;

import java.io.File;
import java.util.Optional;

import log.charter.data.ChartData;
import log.charter.data.config.Config;
import log.charter.data.config.Localization.Label;
import log.charter.data.config.values.PathsConfig;
import log.charter.data.song.Arrangement;
import log.charter.data.song.vocals.VocalPath;
import log.charter.data.undoSystem.UndoSystem;
import log.charter.gui.CharterFrame;
import log.charter.gui.components.tabs.TextTab;
import log.charter.gui.components.utils.ComponentUtils;
import log.charter.gui.components.utils.ComponentUtils.ConfirmAnswer;
import log.charter.io.rs.xml.song.SongArrangement;
import log.charter.io.rs.xml.song.SongArrangementXStreamHandler;
import log.charter.io.rs.xml.vocals.ArrangementVocals;
import log.charter.io.rsc.xml.ChartProject;
import log.charter.services.data.ChartTimeHandler;
import log.charter.services.data.ProjectAudioHandler;
import log.charter.services.data.fixers.ArrangementFixer;
import log.charter.services.editModes.EditMode;
import log.charter.services.editModes.ModeManager;
import log.charter.util.FileChooseUtils;
import log.charter.util.RW;
import log.charter.util.Timer;

public class SongFileHandler {
	public static final String vocalsFileName = "Vocals_RS2.xml";

	private ArrangementFixer arrangementFixer;
	private ChartData chartData;
	private CharterFrame charterFrame;
	private ChartTimeHandler chartTimeHandler;
	private ExistingProjectImporter existingProjectImporter;
	private ModeManager modeManager;
	private ProjectAudioHandler projectAudioHandler;
	private TextTab textTab;
	private UndoSystem undoSystem;

	public void open() {
		if (!askToSaveChanged()) {
			return;
		}

		String startingDir = chartData.path;
		if (startingDir.isEmpty() || !new File(startingDir).exists()) {
			startingDir = PathsConfig.songsPath;
		}

		final File projectFileChosen = chooseFile(charterFrame, startingDir, new String[] { ".rscp" },
				new String[] { Label.CHART_PROJECT.label() });
		if (projectFileChosen == null) {
			return;
		}

		existingProjectImporter.open(projectFileChosen.getAbsolutePath());
	}

	private static String generateArrangementFileName(final int id, final Arrangement arrangementChart) {
		return id + "_" + arrangementChart.arrangementType.name() + "_" + arrangementChart.arrangementSubtype.name()
				+ "_RS2.xml";
	}

	private void writeRSXML(final Timer timer, final File dir, final int id, final Arrangement arrangement) {
		final String arrangementFileName = generateArrangementFileName(id, arrangement);
		final SongArrangement songArrangement = new SongArrangement((int) projectAudioHandler.audioLengthMs(),
				chartData.songChart, arrangement);
		final String xml = SongArrangementXStreamHandler.saveSong(songArrangement);
		timer.addTimestamp("created XML for arrangement " + id);

		RW.write(new File(dir, arrangementFileName), xml, "UTF-8");
		timer.addTimestamp("wrote XML to disk");
	}

	private static String generateVocalPathFileName(final int id, final VocalPath vocals) {
		final String vocalsName = Optional.ofNullable(vocals.name).map(n -> "_" + n.replaceAll("[^ \\-0-9a-zA-Z]", ""))
				.orElse("");

		return id + "_Vocals_" + vocalsName + "_RS2.xml";
	}

	private void writeRSXML(final Timer timer, final File dir, final int id, final VocalPath vocals) {
		if (vocals.vocals.isEmpty()) {
			return;
		}

		final String vocalPathFileName = generateVocalPathFileName(id, vocals);
		final String xml = saveVocals(new ArrangementVocals(chartData.beats(), vocals));
		timer.addTimestamp("created XML for vocals");
		RW.write(new File(dir, vocalPathFileName), xml, "UTF-8");
		timer.addTimestamp("wrote XML to disk");
	}

	private void writeRSXMLs() {
		final Timer timer = new Timer();
		final File dir = new File(chartData.path, "RS XML");
		dir.mkdirs();
		for (final File f : dir.listFiles()) {
			if (f.isFile()) {
				f.delete();
			}
		}
		timer.addTimestamp("created dirs");

		int id = 1;
		for (final Arrangement arrangement : chartData.songChart.arrangements) {
			writeRSXML(timer, dir, id, arrangement);
			id++;
		}

		id = 1;
		for (final VocalPath vocals : chartData.songChart.vocalPaths) {
			writeRSXML(timer, dir, id, vocals);
			id++;
		}

		timer.print("RS XMLs save", Timer.defaultFormat(35));
	}

	public void save() {
		if (modeManager.getMode() == EditMode.EMPTY) {
			return;
		}

		final Timer timer = new Timer();

		arrangementFixer.fixArrangements();
		timer.addTimestamp("arrangementFixer.fixArrangements()");

		final ChartProject project = new ChartProject(chartTimeHandler.time(), modeManager.getMode(), chartData,
				chartData.songChart, projectAudioHandler.getSelectedStem(), textTab.getText());
		timer.addTimestamp("generated project");
		final String xml = writeChartProject(project);
		timer.addTimestamp("wrote project to variable");

		RW.write(new File(chartData.path, chartData.projectFileName), xml, "UTF-8");
		timer.addTimestamp("wrote project to disk");
		writeRSXMLs();
		timer.addTimestamp("wrote RS XML");

		undoSystem.onSave();
		timer.addTimestamp("marked undo saved");

		timer.print("save", Timer.defaultFormat(35));
	}

	public void saveAs() {
		if (modeManager.getMode() == EditMode.EMPTY) {
			return;
		}

		arrangementFixer.fixArrangements();

		final File newDir = FileChooseUtils.chooseDirectory(charterFrame, chartData.path);
		if (newDir == null) {
			return;
		}

		if (!newDir.equals(new File(chartData.path))) {
			final ConfirmAnswer copyAllfiles = ComponentUtils.askYesNo(charterFrame, Label.COPY_ALL_FILES,
					Label.COPY_ALL_FILES_MESSAGE);
			if (copyAllfiles == ConfirmAnswer.YES) {
				RW.copyDir(new File(chartData.path), newDir);
			} else if (copyAllfiles == ConfirmAnswer.NO) {
				final String audioName = ProjectAudioHandler.defaultAudioFileName();
				RW.copy(new File(chartData.path, audioName), new File(newDir, audioName));
			} else {
				return;
			}

			chartData.path = newDir.getAbsolutePath();
			PathsConfig.lastDir = chartData.path;
		}

		PathsConfig.lastPath = new File(chartData.path, chartData.projectFileName).getAbsolutePath();
		Config.markChanged();
		Config.save();

		save();
	}

	public boolean askToSaveChanged() {
		if (undoSystem.isSaved()) {
			return true;
		}

		final ConfirmAnswer answer = askYesNoCancel(charterFrame, Label.UNSAVED_CHANGES_POPUP,
				Label.UNSAVED_CHANGES_MESSAGE);

		switch (answer) {
			case YES:
				save();
			case NO:
				return true;
			default:
				return false;
		}
	}
}
