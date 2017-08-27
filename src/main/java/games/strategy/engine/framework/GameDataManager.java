package games.strategy.engine.framework;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nullable;
import javax.swing.JOptionPane;

import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.commons.io.output.CloseShieldOutputStream;

import com.google.common.annotations.VisibleForTesting;

import games.strategy.debug.ClientLogger;
import games.strategy.engine.ClientContext;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameDataMemento;
import games.strategy.engine.delegate.IDelegate;
import games.strategy.engine.framework.headlessGameServer.HeadlessGameServer;
import games.strategy.persistence.serializable.ProxyableObjectOutputStream;
import games.strategy.triplea.UrlConstants;
import games.strategy.triplea.settings.ClientSetting;
import games.strategy.util.Version;
import games.strategy.util.memento.Memento;
import games.strategy.util.memento.MementoExportException;
import games.strategy.util.memento.MementoExporter;
import games.strategy.util.memento.MementoImportException;
import games.strategy.util.memento.MementoImporter;

/**
 * Responsible for loading saved games, new games from xml, and saving games.
 */
public final class GameDataManager {
  private static final String DELEGATE_START = "<DelegateStart>";
  private static final String DELEGATE_DATA_NEXT = "<DelegateData>";
  private static final String DELEGATE_LIST_END = "<EndDelegateList>";

  private GameDataManager() {}

  /**
   * Loads game data from the specified file.
   *
   * @param file The file from which the game data will be loaded.
   *
   * @return The loaded game data.
   *
   * @throws IOException If an error occurs while loading the game.
   */
  public static GameData loadGame(final File file) throws IOException {
    checkNotNull(file);

    try (final FileInputStream fis = new FileInputStream(file);
        final InputStream is = new BufferedInputStream(fis)) {
      return loadGame(is, getPath(file));
    }
  }

  /**
   * Loads game data from the specified stream.
   *
   * @param is The stream from which the game data will be loaded. The caller is responsible for closing this stream; it
   *        will not be closed when this method returns.
   * @param path The path to the file from which the game data originated or {@code null} if none.
   *
   * @return The loaded game data.
   *
   * @throws IOException If an error occurs while loading the game.
   */
  public static GameData loadGame(final InputStream is, final @Nullable String path) throws IOException {
    checkNotNull(is);

    return ClientSetting.TEST_USE_PROXY_SERIALIZATION.booleanValue()
        ? loadGameInProxySerializationFormat(is)
        : loadGameInSerializationFormat(is, path);
  }

  private static String getPath(final File file) {
    try {
      return file.getCanonicalPath();
    } catch (final IOException e) {
      return file.getPath();
    }
  }

  @VisibleForTesting
  static GameData loadGameInProxySerializationFormat(final InputStream is) throws IOException {
    return fromMemento(loadMemento(new CloseShieldInputStream(is)));
  }

  private static Memento loadMemento(final InputStream is) throws IOException {
    try (final GZIPInputStream gzipis = new GZIPInputStream(is);
        final ObjectInputStream ois = new ObjectInputStream(gzipis)) {
      return (Memento) ois.readObject();
    } catch (final ClassNotFoundException e) {
      throw new IOException(e);
    }
  }

  private static GameData fromMemento(final Memento memento) throws IOException {
    try {
      final MementoImporter<GameData> mementoImporter = GameDataMemento.newImporter();
      return mementoImporter.importMemento(memento);
    } catch (final MementoImportException e) {
      throw new IOException(e);
    }
  }

  private static GameData loadGameInSerializationFormat(
      final InputStream inputStream,
      final @Nullable String savegamePath)
      throws IOException {
    final ObjectInputStream input = new ObjectInputStream(new GZIPInputStream(inputStream));
    try {
      final Version readVersion = (Version) input.readObject();
      final boolean headless = HeadlessGameServer.headless();
      if (!readVersion.equals(ClientContext.engineVersion(), true)) {
        // a hack for now, but a headless server should not try to open any savegame that is not its version
        if (headless) {
          final String message = "Incompatible game save, we are: " + ClientContext.engineVersion()
              + "  Trying to load game created with: " + readVersion;
          HeadlessGameServer.sendChat(message);
          System.out.println(message);
          return null;
        }
        final String error = "<html>Incompatible engine versions. We are: "
            + ClientContext.engineVersion() + " . Trying to load game created with: " + readVersion
            + "<br>To download the latest version of TripleA, Please visit "
            + UrlConstants.LATEST_GAME_DOWNLOAD_WEBSITE + "</html>";
        throw new IOException(error);
      } else if (!headless && readVersion.isGreaterThan(ClientContext.engineVersion(), false)) {
        // we can still load it because first 3 numbers of the version are the same, however this save was made by a
        // newer engine, so prompt the user to upgrade
        final String messageString =
            "<html>Your TripleA engine is OUT OF DATE.  This save was made by a newer version of TripleA."
                + "<br>However, because the first 3 version numbers are the same as your current version, we can "
                + "still open the savegame."
                + "<br><br>This TripleA engine is version "
                + ClientContext.engineVersion().toStringFull("_")
                + " and you are trying to open a savegame made with version " + readVersion.toStringFull("_")
                + "<br><br>To download the latest version of TripleA, Please visit "
                + UrlConstants.LATEST_GAME_DOWNLOAD_WEBSITE
                + "<br><br>It is recommended that you upgrade to the latest version of TripleA before playing this "
                + "savegame."
                + "<br><br>Do you wish to continue and open this save with your current 'old' version?</html>";
        final int answer =
            JOptionPane.showConfirmDialog(null, messageString, "Open Newer Save Game?", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) {
          return null;
        }
      }
      final GameData data = (GameData) input.readObject();
      // TODO: expand this functionality (and keep it updated)
      updateDataToBeCompatibleWithNewEngine(readVersion, data);
      loadDelegates(input, data);
      data.postDeSerialize();
      return data;
    } catch (final ClassNotFoundException cnfe) {
      throw new IOException(cnfe.getMessage());
    }
  }

  /**
   * Use this to keep compatibility between savegames when it is easy to do so.
   * When it is not easy to do so, just make sure to include the last release's .jar file in the "old" folder for
   * triplea.
   * FYI: Engine version numbers work like this with regards to savegames:
   * Any changes to the first 3 digits means that the savegame is not compatible between different engines.
   * While any change only to the 4th (last) digit means that the savegame must be compatible between different engines.
   *
   * @param originalEngineVersion The engine version used to save the specified game data.
   * @param data The game data to be updated.
   */
  private static void updateDataToBeCompatibleWithNewEngine(final Version originalEngineVersion, final GameData data) {
    // whenever this gets out of date, just comment out (but keep as an example, by commenting out)
    /*
     * example1:
     * final Version v1610 = new Version(1, 6, 1, 0);
     * final Version v1620 = new Version(1, 6, 2, 0);
     * if (originalEngineVersion.equals(v1610, false)
     * && ClientContext.engineVersion().getVersion().isGreaterThan(v1610, false)
     * && ClientContext.engineVersion().getVersion().isLessThan(v1620, true))
     * {
     * // if original save was done under 1.6.1.0, and new engine is greater than 1.6.1.0 and less than 1.6.2.0
     * try
     * {
     * if (TechAdvance.getTechAdvances(data).isEmpty())
     * {
     * System.out.println("Adding tech to be compatible with 1.6.1.x");
     * TechAdvance.createDefaultTechAdvances(data);
     * TechAbilityAttachment.setDefaultTechnologyAttachments(data);
     * }
     * } catch (final Exception e)
     * {
     * ClientLogger.logQuietly(e);
     * }
     * }
     */
  }

  private static void loadDelegates(final ObjectInputStream input, final GameData data)
      throws ClassNotFoundException, IOException {
    for (Object endMarker = input.readObject(); !endMarker.equals(DELEGATE_LIST_END); endMarker = input.readObject()) {
      final String name = (String) input.readObject();
      final String displayName = (String) input.readObject();
      final String className = (String) input.readObject();
      final IDelegate instance;
      try {
        instance = (IDelegate) Class.forName(className).getDeclaredConstructor().newInstance();
        instance.initialize(name, displayName);
        data.getDelegateList().addDelegate(instance);
      } catch (final Exception e) {
        ClientLogger.logQuietly(e);
        throw new IOException(e.getMessage());
      }
      final String next = (String) input.readObject();
      if (next.equals(DELEGATE_DATA_NEXT)) {
        instance.loadState((Serializable) input.readObject());
      }
    }
  }

  /**
   * Saves the specified game data to the specified stream.
   *
   * @param os The stream to which the game data will be saved. The caller is responsible for closing this stream; it
   *        will not be closed when this method returns.
   * @param gameData The game data to save.
   *
   * @throws IOException If an error occurs while saving the game.
   */
  public static void saveGame(final OutputStream os, final GameData gameData) throws IOException {
    checkNotNull(os);
    checkNotNull(gameData);

    saveGame(os, gameData, true);
  }

  static void saveGame(
      final OutputStream os,
      final GameData gameData,
      final boolean includeDelegates)
      throws IOException {
    if (ClientSetting.TEST_USE_PROXY_SERIALIZATION.booleanValue()) {
      saveGameInProxySerializationFormat(
          os,
          gameData,
          Collections.singletonMap(GameDataMemento.ExportOptionName.EXCLUDE_DELEGATES, !includeDelegates));
    } else {
      saveGameInSerializationFormat(os, gameData, includeDelegates);
    }
  }

  @VisibleForTesting
  static void saveGameInProxySerializationFormat(
      final OutputStream os,
      final GameData gameData,
      final Map<GameDataMemento.ExportOptionName, Object> optionsByName)
      throws IOException {
    saveMemento(new CloseShieldOutputStream(os), toMemento(gameData, optionsByName));
  }

  private static Memento toMemento(
      final GameData gameData,
      final Map<GameDataMemento.ExportOptionName, Object> optionsByName)
      throws IOException {
    try {
      final MementoExporter<GameData> mementoExporter = GameDataMemento.newExporter(optionsByName);
      return mementoExporter.exportMemento(gameData);
    } catch (final MementoExportException e) {
      throw new IOException(e);
    }
  }

  private static void saveMemento(final OutputStream os, final Memento memento) throws IOException {
    try (final GZIPOutputStream gzipos = new GZIPOutputStream(os);
        final ObjectOutputStream oos = new ProxyableObjectOutputStream(gzipos, ProxyRegistries.GAME_DATA_MEMENTO)) {
      oos.writeObject(memento);
    }
  }

  private static void saveGameInSerializationFormat(
      final OutputStream sink,
      final GameData data,
      final boolean saveDelegateInfo)
      throws IOException {
    // write internally first in case of error
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream(25000);
    final ObjectOutputStream outStream = new ObjectOutputStream(bytes);
    outStream.writeObject(ClientContext.engineVersion());
    data.acquireReadLock();
    try {
      outStream.writeObject(data);
      if (saveDelegateInfo) {
        writeDelegates(data, outStream);
      } else {
        outStream.writeObject(DELEGATE_LIST_END);
      }
    } finally {
      data.releaseReadLock();
    }
    try (final GZIPOutputStream zippedOut = new GZIPOutputStream(sink)) {
      // now write to file
      zippedOut.write(bytes.toByteArray());
    }
  }

  private static void writeDelegates(final GameData data, final ObjectOutputStream out) throws IOException {
    final Iterator<IDelegate> iter = data.getDelegateList().iterator();
    while (iter.hasNext()) {
      out.writeObject(DELEGATE_START);
      final IDelegate delegate = iter.next();
      // write out the delegate info
      out.writeObject(delegate.getName());
      out.writeObject(delegate.getDisplayName());
      out.writeObject(delegate.getClass().getName());
      out.writeObject(DELEGATE_DATA_NEXT);
      out.writeObject(delegate.saveState());
    }
    // mark end of delegate section
    out.writeObject(DELEGATE_LIST_END);
  }
}
