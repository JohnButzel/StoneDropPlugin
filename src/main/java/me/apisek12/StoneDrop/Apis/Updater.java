package me.apisek12.StoneDrop.Apis;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

/**
 * Check for updates on GitHub Releases for a given plugin, and download the updates if needed.
 */
public class Updater {

    // GitHub API endpoint
    private static final String GITHUB_API = "https://api.github.com/repos";
    // User-agent when querying GitHub
    private static final String USER_AGENT = "StoneDrop-Updater";
    // If the version tag contains one of these, don't update.
    private static final String[] NO_UPDATE_TAG = { "-DEV", "-PRE", "-SNAPSHOT" };
    // Used for downloading files
    private static final int BYTE_SIZE = 1024;

    /* User-provided variables */
    private final Plugin plugin;
    private final UpdateType type;
    private final boolean announce;
    private final File file;
    private final File updateFolder;
    private final UpdateCallback callback;
    private final String repoOwner;
    private final String repoName;

    /* Collected from GitHub API */
    private String versionName;
    private String versionLink;

    /* Update process variables */
    private URL url;
    private Thread thread;
    private Updater.UpdateResult result = Updater.UpdateResult.SUCCESS;

    /**
     * Gives the developer the result of the update process.
     */
    public enum UpdateResult {
        SUCCESS,
        NO_UPDATE,
        DISABLED,
        FAIL_DOWNLOAD,
        FAIL_DBO,
        FAIL_NOVERSION,
        FAIL_BADID,
        UPDATE_AVAILABLE
    }

    /**
     * Allows the developer to specify the type of update that will be run.
     */
    public enum UpdateType {
        DEFAULT,
        NO_VERSION_CHECK,
        NO_DOWNLOAD
    }

    /**
     * Initialize the updater.
     *
     * @param plugin     The plugin that is checking for an update.
     * @param repoOwner  The GitHub repository owner (e.g., "JohnButzel").
     * @param repoName   The GitHub repository name (e.g., "StoneDropPlugin").
     * @param file       The plugin file (jar).
     * @param type       The type of update check to run.
     * @param announce   True to announce progress in console.
     */
    public Updater(Plugin plugin, String repoOwner, String repoName, File file, UpdateType type, boolean announce) {
        this(plugin, repoOwner, repoName, file, type, null, announce);
    }

    /**
     * Initialize the updater with a callback.
     */
    public Updater(Plugin plugin, String repoOwner, String repoName, File file, UpdateType type, UpdateCallback callback, boolean announce) {
        this.plugin = plugin;
        this.repoOwner = repoOwner;
        this.repoName = repoName;
        this.type = type;
        this.announce = announce;
        this.file = file;
        this.updateFolder = this.plugin.getServer().getUpdateFolderFile();
        this.callback = callback;

        try {
            this.url = new URL(GITHUB_API + "/" + repoOwner + "/" + repoName + "/releases/latest");
        } catch (final MalformedURLException e) {
            this.result = UpdateResult.FAIL_BADID;
        }

        if (this.result != UpdateResult.FAIL_BADID) {
            this.thread = new Thread(new UpdateRunnable());
            this.thread.start();
        } else {
            runUpdater();
        }
    }

    /**
     * Get the result of the update process.
     */
    public Updater.UpdateResult getResult() {
        this.waitForThread();
        return this.result;
    }

    /**
     * Get the latest version name (tag name from GitHub).
     */
    public String getLatestName() {
        this.waitForThread();
        return this.versionName;
    }

    /**
     * Get the latest version's download link.
     */
    public String getLatestFileLink() {
        this.waitForThread();
        return this.versionLink;
    }

    private void waitForThread() {
        if ((this.thread != null) && this.thread.isAlive()) {
            try {
                this.thread.join();
            } catch (final InterruptedException e) {
                this.plugin.getLogger().log(Level.SEVERE, null, e);
            }
        }
    }

    /**
     * Save the downloaded update into the server's update folder.
     */
    private void saveFile(String fileName) {
        final File folder = this.updateFolder;
        deleteOldFiles();
        if (!folder.exists()) {
            this.fileIOOrError(folder, folder.mkdir(), true);
        }
        downloadFile();

        final File dFile = new File(folder.getAbsolutePath(), fileName);
        if (dFile.getName().endsWith(".zip")) {
            this.unzip(dFile.getAbsolutePath());
        }
        if (this.announce) {
            this.plugin.getLogger().info("Finished updating.");
        }
    }

    /**
     * Download a file and save it to the update folder.
     */
    private void downloadFile() {
        BufferedInputStream in = null;
        FileOutputStream fout = null;
        try {
            URL fileUrl = followRedirects(this.versionLink);
            final int fileLength = fileUrl.openConnection().getContentLength();
            in = new BufferedInputStream(fileUrl.openStream());
            fout = new FileOutputStream(new File(this.updateFolder, file.getName()));

            final byte[] data = new byte[Updater.BYTE_SIZE];
            int count;
            if (this.announce) {
                this.plugin.getLogger().info("About to download a new update: " + this.versionName);
            }
            long downloaded = 0;
            while ((count = in.read(data, 0, Updater.BYTE_SIZE)) != -1) {
                downloaded += count;
                fout.write(data, 0, count);
                final int percent = (int) ((downloaded * 100) / fileLength);
                if (this.announce && ((percent % 10) == 0)) {
                    this.plugin.getLogger().info("Downloading update: " + percent + "% of " + fileLength + " bytes.");
                }
            }
        } catch (Exception ex) {
            this.plugin.getLogger().log(Level.WARNING, "The auto-updater tried to download a new update, but was unsuccessful.", ex);
            this.result = Updater.UpdateResult.FAIL_DOWNLOAD;
        } finally {
            try { if (in != null) in.close(); } catch (final IOException ex) { this.plugin.getLogger().log(Level.SEVERE, null, ex); }
            try { if (fout != null) fout.close(); } catch (final IOException ex) { this.plugin.getLogger().log(Level.SEVERE, null, ex); }
        }
    }

    private URL followRedirects(String location) throws IOException {
        URL resourceUrl, base, next;
        HttpURLConnection conn;
        String redLoc;
        while (true) {
            resourceUrl = new URL(location);
            conn = (HttpURLConnection) resourceUrl.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0...");
            switch (conn.getResponseCode()) {
                case HttpURLConnection.HTTP_MOVED_PERM:
                case HttpURLConnection.HTTP_MOVED_TEMP:
                    redLoc = conn.getHeaderField("Location");
                    base = new URL(location);
                    next = new URL(base, redLoc);
                    location = next.toExternalForm();
                    continue;
            }
            break;
        }
        return conn.getURL();
    }

    private void deleteOldFiles() {
        File[] list = listFilesOrError(this.updateFolder);
        for (final File xFile : list) {
            if (xFile.getName().endsWith(".zip")) {
                this.fileIOOrError(xFile, xFile.mkdir(), true);
            }
        }
    }

    private void unzip(String file) {
        final File fSourceZip = new File(file);
        try {
            final String zipPath = file.substring(0, file.length() - 4);
            ZipFile zipFile = new ZipFile(fSourceZip);
            Enumeration<? extends ZipEntry> e = zipFile.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                File destinationFilePath = new File(zipPath, entry.getName());
                this.fileIOOrError(destinationFilePath.getParentFile(), destinationFilePath.getParentFile().mkdirs(), true);
                if (!entry.isDirectory()) {
                    final BufferedInputStream bis = new BufferedInputStream(zipFile.getInputStream(entry));
                    int b;
                    final byte[] buffer = new byte[Updater.BYTE_SIZE];
                    final FileOutputStream fos = new FileOutputStream(destinationFilePath);
                    final BufferedOutputStream bos = new BufferedOutputStream(fos, Updater.BYTE_SIZE);
                    while ((b = bis.read(buffer, 0, Updater.BYTE_SIZE)) != -1) {
                        bos.write(buffer, 0, b);
                    }
                    bos.flush();
                    bos.close();
                    bis.close();
                    final String name = destinationFilePath.getName();
                    if (name.endsWith(".jar") && this.pluginExists(name)) {
                        File output = new File(this.updateFolder, name);
                        this.fileIOOrError(output, destinationFilePath.renameTo(output), true);
                    }
                }
            }
            zipFile.close();
            moveNewZipFiles(zipPath);
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.SEVERE, "The auto-updater tried to unzip a new update file, but was unsuccessful.", e);
            this.result = Updater.UpdateResult.FAIL_DOWNLOAD;
        } finally {
            this.fileIOOrError(fSourceZip, fSourceZip.delete(), false);
        }
    }

    private void moveNewZipFiles(String zipPath) {
        File[] list = listFilesOrError(new File(zipPath));
        for (final File dFile : list) {
            if (dFile.isDirectory() && this.pluginExists(dFile.getName())) {
                final File oFile = new File(this.plugin.getDataFolder().getParent(), dFile.getName());
                final File[] dList = listFilesOrError(dFile);
                final File[] oList = listFilesOrError(oFile);
                for (File cFile : dList) {
                    boolean found = false;
                    for (final File xFile : oList) {
                        if (xFile.getName().equals(cFile.getName())) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        File output = new File(oFile, cFile.getName());
                        this.fileIOOrError(output, cFile.renameTo(output), true);
                    } else {
                        this.fileIOOrError(cFile, cFile.delete(), false);
                    }
                }
            }
            this.fileIOOrError(dFile, dFile.delete(), false);
        }
        File zip = new File(zipPath);
        this.fileIOOrError(zip, zip.delete(), false);
    }

    private boolean pluginExists(String name) {
        File[] plugins = listFilesOrError(new File("plugins"));
        for (final File file : plugins) {
            if (file.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the plugin should update by comparing versions.
     * Uses semantic version comparison (tag_name like "v3.0.0").
     */
    private boolean versionCheck() {
        if (this.type != UpdateType.NO_VERSION_CHECK) {
            final String localVersion = this.plugin.getDescription().getVersion();
            final String remoteVersion = stripV(this.versionName);

            if (this.hasTag(localVersion)) {
                this.result = Updater.UpdateResult.NO_UPDATE;
                return false;
            }

            if (!this.shouldUpdate(localVersion, remoteVersion)) {
                this.result = Updater.UpdateResult.NO_UPDATE;
                return false;
            }
        }
        return true;
    }

    /**
     * Strip leading 'v' from a version string, e.g. "v3.0.0" -> "3.0.0".
     */
    private String stripV(String version) {
        if (version != null && version.length() > 1 && version.charAt(0) == 'v') {
            return version.substring(1);
        }
        return version;
    }

    /**
     * Compare two semantic versions (e.g. "3.0.0" and "2.2.1").
     * Returns true if remoteVersion is strictly greater than localVersion.
     */
    public boolean shouldUpdate(String localVersion, String remoteVersion) {
        if (localVersion == null || remoteVersion == null) {
            return false;
        }
        String[] localParts = localVersion.split("\\.");
        String[] remoteParts = remoteVersion.split("\\.");
        int maxLen = Math.max(localParts.length, remoteParts.length);
        for (int i = 0; i < maxLen; i++) {
            int localNum = i < localParts.length ? tryParse(localParts[i]) : 0;
            int remoteNum = i < remoteParts.length ? tryParse(remoteParts[i]) : 0;
            if (remoteNum > localNum) return true;
            if (remoteNum < localNum) return false;
        }
        return false;
    }

    private int tryParse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean hasTag(String version) {
        if (version == null) return false;
        for (final String tag : Updater.NO_UPDATE_TAG) {
            if (version.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fetch the latest release info from GitHub Releases API.
     * On failure, silently gives up (no console spam).
     */
    private boolean read() {
        try {
            final HttpURLConnection conn = (HttpURLConnection) this.url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setRequestProperty("User-Agent", Updater.USER_AGENT);

            final int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                this.result = UpdateResult.FAIL_DBO;
                return false;
            }

            final BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            final String response = reader.readLine();

            final JSONObject release = (JSONObject) JSONValue.parse(response);
            if (release == null) {
                this.result = UpdateResult.FAIL_BADID;
                return false;
            }

            this.versionName = (String) release.get("tag_name");

            // Find the first asset with a .jar extension
            final JSONArray assets = (JSONArray) release.get("assets");
            if (assets != null && !assets.isEmpty()) {
                for (Object obj : assets) {
                    JSONObject asset = (JSONObject) obj;
                    String assetName = (String) asset.get("name");
                    if (assetName != null && assetName.endsWith(".jar")) {
                        this.versionLink = (String) asset.get("browser_download_url");
                        break;
                    }
                }
                // Fallback to first asset if no .jar found
                if (this.versionLink == null) {
                    JSONObject firstAsset = (JSONObject) assets.get(0);
                    this.versionLink = (String) firstAsset.get("browser_download_url");
                }
            }

            return true;
        } catch (final IOException e) {
            this.result = UpdateResult.FAIL_DBO;
            return false;
        }
    }

    private void fileIOOrError(File file, boolean result, boolean create) {
        if (!result) {
            this.plugin.getLogger().severe("The updater could not " + (create ? "create" : "delete") + " file at: " + file.getAbsolutePath());
        }
    }

    private File[] listFilesOrError(File folder) {
        File[] contents = folder.listFiles();
        if (contents == null) {
            this.plugin.getLogger().severe("The updater could not access files at: " + this.updateFolder.getAbsolutePath());
            return new File[0];
        }
        return contents;
    }

    /**
     * Called on main thread when the Updater has finished working.
     */
    public interface UpdateCallback {
        void onFinish(Updater updater);
    }

    private class UpdateRunnable implements Runnable {
        @Override
        public void run() {
            runUpdater();
        }
    }

    private void runUpdater() {
        if (this.url != null && (this.read() && this.versionCheck())) {
            if ((this.versionLink != null) && (this.type != UpdateType.NO_DOWNLOAD)) {
                String name = this.file.getName();
                this.saveFile(name);
            } else {
                this.result = UpdateResult.UPDATE_AVAILABLE;
            }
        }

        if (this.callback != null) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    runCallback();
                }
            }.runTask(this.plugin);
        }
    }

    private void runCallback() {
        this.callback.onFinish(this);
    }
}