package dev.asarix.skyblockflow.ui;

import dev.asarix.skyblockflow.SkyblockFlowClient;
import dev.asarix.skyblockflow.core.*;
import dev.asarix.skyblockflow.core.Models.*;
import java.nio.file.*;
import java.util.List;
import net.minecraft.util.Util;

public final class SongTimelineTransferScreen extends FormScreen {
  private final SongAnalysisScreen editor;
  private final CelebrationSettings settings;
  private final HitTimeline document;
  private boolean busy;
  private Path lastExport;

  public SongTimelineTransferScreen(
      SkyblockFlowClient app,
      SongAnalysisScreen editor,
      CelebrationSettings settings,
      HitTimeline document) {
    super(app, editor, "Hit timeline · import / export");
    this.editor = editor;
    this.settings = settings;
    this.document = document.copy();
  }

  @Override
  protected boolean readOnly() {
    return true;
  }

  @Override
  protected String description() {
    return "JSON files work in both SkyblockFlow and SkyblockFlow with the exact same MP3.";
  }

  @Override
  protected void buildForm() {
    field(
        "path",
        "JSON file path",
        app.store
            .directory()
            .resolve("timeline-exports")
            .resolve(Path.of(settings.sound).getFileName().toString() + ".hits.json")
            .toString(),
        0,
        0,
        bodyW);
    action(
        "Export draft timeline",
        0,
        56,
        bodyW,
        () -> {
          if (busy) return;
          try {
            Path destination = Path.of(value("path"));
            busy = true;
            app.sounds
                .exportTimeline(destination, settings.sound, document)
                .whenComplete(
                    (path, error) ->
                        minecraft.execute(
                            () -> {
                              busy = false;
                              if (error != null) notice = SongAnalysisScreen.error(error);
                              else {
                                lastExport = path;
                                notice = "Exported: " + path;
                              }
                            }));
          } catch (Exception error) {
            busy = false;
            notice = error.getMessage();
          }
        });
    action(
        "Import into editor",
        0,
        90,
        bodyW,
        () -> {
          try {
            importFile(Path.of(value("path")));
          } catch (Exception error) {
            notice = error.getMessage();
          }
        });
    action(
        "Open export folder",
        0,
        124,
        bodyW,
        () -> {
          Path folder =
              lastExport == null
                  ? app.store.directory().resolve("timeline-exports")
                  : lastExport.getParent();
          try {
            Files.createDirectories(folder);
            Util.getPlatform().openPath(folder);
          } catch (Exception error) {
            notice = error.getMessage();
          }
        });
    label("You can also drop a JSON file onto this page or the timeline.", 0, 166);
    label("Imports remain drafts until you choose Save hits in the editor.", 0, 190);
    label("Exports include the audio fingerprint, detector settings and corrections.", 0, 214);
    label("No audio or timeline is uploaded automatically.", 0, 238);
  }

  private void importFile(Path path) {
    if (busy) return;
    busy = true;
    notice = "Checking timeline...";
    app.sounds
        .importTimeline(path, settings.sound, document.durationMs)
        .whenComplete(
            (doc, error) ->
                minecraft.execute(
                    () -> {
                      busy = false;
                      if (error != null) {
                        notice = SongAnalysisScreen.error(error);
                        return;
                      }
                      editor.imported(doc);
                      minecraft.gui.setScreen(editor);
                    }));
  }

  @Override
  public void onFilesDrop(List<Path> paths) {
    if (paths.size() == 1) importFile(paths.getFirst());
  }

  @Override
  protected void commit() {
    onClose();
  }

  @Override
  public void onClose() {
    if (!busy) super.onClose();
    else notice = "Wait for the file operation to finish.";
  }
}
