package astroscalapng.gui

import astroscalapng.Batch.{FileStatus, Options, Progress, Summary}
import astroscalapng.{Batch, BatchError, Version}

import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.{Application, Platform}
import javafx.geometry.{Insets, Pos}
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.{BorderPane, GridPane, HBox, Priority, VBox}
import javafx.scene.paint.Color
import javafx.scene.text.{Text, TextFlow}
import javafx.stage.{DirectoryChooser, FileChooser, Stage}

/** Plain entry point: launching a JavaFX `Application` subclass directly from an
  * unnamed module fails, so the launcher is a separate class.
  */
object Launcher:
  def main(args: Array[String]): Unit = Application.launch(classOf[GuiApp], args*)

/** A JavaFX front end for the same batch runner the CLI uses. This is a new GUI
  * rather than a port of the Rust egui window, but offers the same capability:
  * pick input files or a folder, an output folder and a stamp font, set the
  * options and the worker count, run, watch per-file progress, cancel.
  */
class GuiApp extends Application:

  private val inputField  = new TextField()
  inputField.setPromptText("(current folder if left empty)")
  private val outputField = new TextField()
  private val fontField   = new TextField()

  private val recursiveBox = new CheckBox("Recurse into subfolders")
  private val overwriteBox = new CheckBox("Overwrite existing .png files")
  private val resizeBox    = new CheckBox("Resize to 4K and stamp the object name")
  private val pngOnlyBox   = new CheckBox("Only re-process existing .png files")
  private val filenameBox  = new CheckBox("Stamp the plain file name (no online lookup)")

  private val concurrencySpinner = new Spinner[Integer](0, 64, 0)
  private val log                = new TextFlow()
  private val logScroll          = new ScrollPane(log)
  private val progressBar        = new ProgressBar(0.0)
  private val statusLabel        = new Label("Ready.")
  private val convertButton      = new Button("Convert")
  private val cancelButton       = new Button("Cancel")
  private val fileList           = new ListView[String]()

  private var explicitFiles: Vector[Path] = Vector.empty
  private val cancelFlag                  = new AtomicBoolean(false)
  private var worker: Option[Thread]       = None

  override def start(stage: Stage): Unit =
    stage.setTitle(s"AstroScalaPNG ${Version.value}")

    resizeBox.setSelected(true)
    concurrencySpinner.setEditable(true)
    logScroll.setFitToWidth(true)
    logScroll.setPrefHeight(260)
    cancelButton.setDisable(true)
    fileList.setPrefHeight(90)
    fileList.setPlaceholder(new Label("No explicit files: the input folder is scanned."))

    // Command-line arguments are treated as explicit files, like the Rust GUI.
    val args = getParameters.getRaw
    args.forEach { a =>
      val p = Paths.get(a)
      if Files.isRegularFile(p) then explicitFiles = explicitFiles :+ p
    }
    refreshFileList()

    val grid = new GridPane()
    grid.setHgap(8)
    grid.setVgap(8)
    grid.setPadding(new Insets(12))

    def row(n: Int, label: String, field: TextField, browse: Button): Unit =
      GridPane.setHgrow(field, Priority.ALWAYS)
      grid.add(new Label(label), 0, n)
      grid.add(field, 1, n)
      grid.add(browse, 2, n)

    val inputBrowse = new Button("Folder…")
    inputBrowse.setOnAction(_ => chooseDirectory(stage, "Input folder").foreach(p => inputField.setText(p.toString)))
    val outputBrowse = new Button("Folder…")
    outputBrowse.setOnAction(_ => chooseDirectory(stage, "Output folder").foreach(p => outputField.setText(p.toString)))
    val fontBrowse = new Button("Font…")
    fontBrowse.setOnAction { _ =>
      val chooser = new FileChooser()
      chooser.setTitle("Stamp font")
      chooser.getExtensionFilters.add(new FileChooser.ExtensionFilter("Fonts", "*.ttf", "*.otf"))
      Option(chooser.showOpenDialog(stage)).foreach(f => fontField.setText(f.getAbsolutePath))
    }

    row(0, "Input folder", inputField, inputBrowse)
    row(1, "Output folder", outputField, outputBrowse)
    row(2, "Stamp font", fontField, fontBrowse)
    outputField.setPromptText("(optional - next to the source files)")
    fontField.setPromptText("(optional - bundled DejaVu Sans Condensed Bold)")

    val addFiles = new Button("Add files…")
    addFiles.setOnAction { _ =>
      val chooser = new FileChooser()
      chooser.setTitle("Files to convert")
      chooser.getExtensionFilters.add(
        new FileChooser.ExtensionFilter("Images", "*.xisf", "*.fits", "*.fit", "*.fts", "*.png")
      )
      Option(chooser.showOpenMultipleDialog(stage)).foreach { files =>
        files.forEach(f => explicitFiles = explicitFiles :+ f.toPath)
        refreshFileList()
      }
    }
    val clearFiles = new Button("Clear files")
    clearFiles.setOnAction { _ =>
      explicitFiles = Vector.empty
      refreshFileList()
    }

    val options = new VBox(6, recursiveBox, overwriteBox, resizeBox, pngOnlyBox, filenameBox)
    options.setPadding(new Insets(0, 12, 0, 12))

    val concurrencyBox = new HBox(
      8,
      new Label("Parallel files (0 = auto):"),
      concurrencySpinner
    )
    concurrencyBox.setAlignment(Pos.CENTER_LEFT)
    concurrencyBox.setPadding(new Insets(0, 12, 0, 12))

    val fileButtons = new HBox(8, addFiles, clearFiles)
    fileButtons.setPadding(new Insets(0, 12, 0, 12))

    convertButton.setOnAction(_ => startRun())
    cancelButton.setOnAction(_ => cancelFlag.set(true))
    val actions = new HBox(8, convertButton, cancelButton, statusLabel)
    actions.setAlignment(Pos.CENTER_LEFT)
    actions.setPadding(new Insets(12))

    val top = new VBox(
      8,
      grid,
      fileButtons,
      fileList,
      new Separator(),
      options,
      concurrencyBox,
      new Separator(),
      actions,
      progressBar
    )
    progressBar.setMaxWidth(Double.MaxValue)
    VBox.setMargin(progressBar, new Insets(0, 12, 8, 12))

    val rootPane = new BorderPane()
    rootPane.setTop(top)
    rootPane.setCenter(logScroll)
    BorderPane.setMargin(logScroll, new Insets(0, 12, 12, 12))

    stage.setScene(new Scene(rootPane, 900, 720))
    stage.show()

  private def refreshFileList(): Unit =
    fileList.getItems.setAll(explicitFiles.map(_.toString)*)

  private def chooseDirectory(stage: Stage, title: String): Option[Path] =
    val chooser = new DirectoryChooser()
    chooser.setTitle(title)
    Option(chooser.showDialog(stage)).map(_.toPath)

  private def currentOptions(): Options =
    Options(
      inputDir = Paths.get(Option(inputField.getText).filter(_.trim.nonEmpty).getOrElse(".")),
      outputDir = Option(outputField.getText).map(_.trim).filter(_.nonEmpty).map(Paths.get(_)),
      recursive = recursiveBox.isSelected,
      overwrite = overwriteBox.isSelected,
      resize4k = resizeBox.isSelected,
      pngOnly = pngOnlyBox.isSelected,
      font = Option(fontField.getText).map(_.trim).filter(_.nonEmpty).map(Paths.get(_)),
      lookup = !filenameBox.isSelected,
      files = explicitFiles,
      concurrency = concurrencySpinner.getValue.intValue()
    )

  // Segment colors match the Rust GUI's log coloring exactly.
  private val okColor    = Color.rgb(120, 200, 120)
  private val errorColor = Color.rgb(230, 120, 120)
  private val labelColor = Color.rgb(140, 190, 255)
  private val noteColor  = Color.rgb(235, 180, 90)

  /** Appends one or more colored runs, then a newline, to the log. `None`
    * keeps the default (black) text color.
    */
  private def appendSegments(segments: (String, Option[Color])*): Unit =
    Platform.runLater(() =>
      for (text, colorOpt) <- segments do
        val t = new Text(text)
        colorOpt.foreach(t.setFill)
        log.getChildren.add(t)
      log.getChildren.add(new Text("\n"))
      logScroll.setVvalue(1.0)
    )

  private def clearLog(): Unit = log.getChildren.clear()

  private def startRun(): Unit =
    if worker.exists(_.isAlive) then return
    val opts = currentOptions()
    cancelFlag.set(false)
    clearLog()
    progressBar.setProgress(0.0)
    convertButton.setDisable(true)
    cancelButton.setDisable(false)
    statusLabel.setText("Converting…")

    val t = new Thread(
      () =>
        val result =
          try
            val summary = Batch.run(
              opts,
              cancelFlag,
              (p: Progress) =>
                val rel = p.rel.toString
                p.status match
                  case FileStatus.Ok =>
                    val tail: Seq[(String, Option[Color])] = p.label match
                      case Some(label) => Seq(("  ->  ", None), (label, Some(labelColor)))
                      case None        => Seq.empty
                    appendSegments((("OK    ", Some(okColor)) +: (rel, None) +: tail)*)
                  case FileStatus.Skipped =>
                    appendSegments(("SKIP  ", Some(Color.GRAY)), (rel, None))
                  case FileStatus.Failed(e) =>
                    appendSegments(("ERROR ", Some(errorColor)), (rel, None), (": ", None), (e, Some(errorColor)))
                p.note.foreach(note => appendSegments(("      note: ", None), (note, Some(noteColor))))
                val done = p.index.toDouble / math.max(p.total, 1)
                Platform.runLater(() => progressBar.setProgress(done))
            )
            Right(summary)
          catch
            case e: BatchError => Left(e.getMessage)
            case e: Throwable  => Left(Option(e.getMessage).getOrElse(e.toString))

        Platform.runLater { () =>
          convertButton.setDisable(false)
          cancelButton.setDisable(true)
          result match
            case Left(err) =>
              statusLabel.setText("Failed.")
              appendSegments((err, Some(errorColor)))
            case Right(summary) => finish(opts, summary)
        },
      "astroscalapng-batch"
    )
    t.setDaemon(true)
    worker = Some(t)
    t.start()

  private def finish(opts: Options, summary: Summary): Unit =
    val verb = if opts.pngOnly then "Processed" else "Converted"
    if summary.total == 0 then
      progressBar.setProgress(0.0)
      statusLabel.setText(s"No ${opts.inputKind} files found.")
      appendSegments((s"No ${opts.inputKind} files found.", None))
    else
      progressBar.setProgress(1.0)
      val line =
        s"$verb: ${summary.converted}   Skipped: ${summary.skipped}   Failed: ${summary.failed}"
      val lineColor = if summary.failed > 0 then Some(errorColor) else None
      appendSegments(("\n", None), (line, lineColor))
      summary.warnings.foreach(w => appendSegments((s"WARNING: $w", Some(noteColor))))
      statusLabel.setText(if summary.cancelled then s"Cancelled. $line" else line)
