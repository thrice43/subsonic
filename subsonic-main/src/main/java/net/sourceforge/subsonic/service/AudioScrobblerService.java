/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package net.sourceforge.subsonic.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpConnectionParams;

import net.sourceforge.subsonic.Logger;
import net.sourceforge.subsonic.domain.MusicFile;
import net.sourceforge.subsonic.domain.UserSettings;
import net.sourceforge.subsonic.util.StringUtil;

/**
 * Provides services for "audioscrobbling", which is the process of
 * registering what songs are played at www.last.fm.
 * <p/>
 * See http://www.last.fm/api/submissions
 *
 * @author Sindre Mehus
 */
public class AudioScrobblerService {

    private static final Logger LOG = Logger.getLogger(AudioScrobblerService.class);
    private static final int MAX_PENDING_REGISTRATION = 2000;
    private static final long MIN_REGISTRATION_INTERVAL = 30000L;

    private RegistrationThread thread;
    private final Map<String, Long> lastRegistrationTimes = new HashMap<String, Long>();
    private final LinkedBlockingQueue<RegistrationData> queue = new LinkedBlockingQueue<RegistrationData>();

    private SettingsService settingsService;

    /**
     * Registers the given music file at www.last.fm. This method returns immediately, the actual registration is done
     * by a separate thread.
     *
     * @param musicFile  The music file to register.
     * @param username   The user which played the music file.
     * @param submission Whether this is a submission or a now playing notification.
     */
    public synchronized void register(MusicFile musicFile, String username, boolean submission) {

        if (thread == null) {
            thread = new RegistrationThread();
            thread.start();
        }

        if (queue.size() >= MAX_PENDING_REGISTRATION) {
            LOG.warn("Last.fm scrobbler queue is full. Ignoring " + musicFile);
            return;
        }

        RegistrationData registrationData = createRegistrationData(musicFile, username, submission);
        if (registrationData == null) {
            return;
        }

        try {
            queue.put(registrationData);
        } catch (InterruptedException x) {
            LOG.warn("Interrupted while queuing Last.fm scrobble.", x);
        }
    }

    /**
     * Returns registration details, or <code>null</code> if not eligible for registration.
     */
    private RegistrationData createRegistrationData(MusicFile musicFile, String username, boolean submission) {

        if (musicFile == null || musicFile.isVideo()) {
            return null;
        }

        MusicFile.MetaData metaData = musicFile.getMetaData();
        if (metaData == null) {
            return null;
        }

        UserSettings userSettings = settingsService.getUserSettings(username);
        if (!userSettings.isLastFmEnabled() || userSettings.getLastFmUsername() == null || userSettings.getLastFmPassword() == null) {
            return null;
        }

        long now = System.currentTimeMillis();

        // Don't register submissions more often than every 30 seconds.
        if (submission) {
            Long lastRegistrationTime = lastRegistrationTimes.get(username);
            if (lastRegistrationTime != null && now - lastRegistrationTime < MIN_REGISTRATION_INTERVAL) {
                return null;
            }
            lastRegistrationTimes.put(username, now);
        }

        RegistrationData reg = new RegistrationData();
        reg.username = userSettings.getLastFmUsername();
        reg.password = userSettings.getLastFmPassword();
        reg.artist = metaData.getArtist();
        reg.album = metaData.getAlbum();
        reg.title = metaData.getTitle();
        reg.duration = metaData.getDuration() == null ? 0 : metaData.getDuration();
        reg.time = new Date(now);
        reg.submission = submission;

        return reg;
    }

    /**
     * Scrobbles the given song data at last.fm, using the protocol defined at http://www.last.fm/api/submissions.
     *
     * @param registrationData Registration data for the song.
     */
    private void scrobble(RegistrationData registrationData) throws Exception {
        if (registrationData == null) {
            return;
        }

        String[] lines = authenticate(registrationData);
        if (lines == null) {
            return;
        }

        String sessionId = lines[1];
        String nowPlayingUrl = lines[2];
        String submissionUrl = lines[3];

        if (registrationData.submission) {
            lines = registerSubmission(registrationData, sessionId, submissionUrl);
        } else {
            lines = registerNowPlaying(registrationData, sessionId, nowPlayingUrl);
        }

        if (lines[0].startsWith("FAILED")) {
            LOG.warn("Failed to scrobble song '" + registrationData.title + "' at Last.fm: " + lines[0]);
        } else if (lines[0].startsWith("BADSESSION")) {
            LOG.warn("Failed to scrobble song '" + registrationData.title + "' at Last.fm.  Invalid session.");
        } else if (lines[0].startsWith("OK")) {
            LOG.debug("Successfully registered " + (registrationData.submission ? "submission" : "now playing") +
                    " for song '" + registrationData.title + "' for user " + registrationData.username + " at Last.fm.");
        }
    }

    /**
     * Returns the following lines if authentication succeeds:
     * <p/>
     * Line 0: Always "OK"
     * Line 1: Session ID, e.g., "17E61E13454CDD8B68E8D7DEEEDF6170"
     * Line 2: URL to use for now playing, e.g., "http://post.audioscrobbler.com:80/np_1.2"
     * Line 3: URL to use for submissions, e.g., "http://post2.audioscrobbler.com:80/protocol_1.2"
     * <p/>
     * If authentication fails, <code>null</code> is returned.
     */
    private String[] authenticate(RegistrationData registrationData) throws Exception {
        String clientId = "sub";
        String clientVersion = "0.1";
        long timestamp = System.currentTimeMillis() / 1000L;
        String authToken = calculateAuthenticationToken(registrationData.password, timestamp);
        String[] lines = executeGetRequest("http://post.audioscrobbler.com/?hs=true&p=1.2.1&c=" + clientId + "&v=" +
                clientVersion + "&u=" + registrationData.username + "&t=" + timestamp + "&a=" + authToken);

        if (lines[0].startsWith("BANNED")) {
            LOG.warn("Failed to scrobble song '" + registrationData.title + "' at Last.fm. Client version is banned.");
            return null;
        }

        if (lines[0].startsWith("BADAUTH")) {
            LOG.warn("Failed to scrobble song '" + registrationData.title + "' at Last.fm. Wrong username or password.");
            return null;
        }

        if (lines[0].startsWith("BADTIME")) {
            LOG.warn("Failed to scrobble song '" + registrationData.title + "' at Last.fm. Bad timestamp, please check local clock.");
            return null;
        }

        if (lines[0].startsWith("FAILED")) {
            LOG.warn("Failed to scrobble song '" + registrationData.title + "' at Last.fm: " + lines[0]);
            return null;
        }

        if (!lines[0].startsWith("OK")) {
            LOG.warn("Failed to scrobble song '" + registrationData.title + "' at Last.fm.  Unknown response: " + lines[0]);
            return null;
        }

        return lines;
    }

    private String[] registerSubmission(RegistrationData registrationData, String sessionId, String url) throws IOException {
        Map<String, String> params = new HashMap<String, String>();
        params.put("s", sessionId);
        params.put("a[0]", registrationData.artist);
        params.put("t[0]", registrationData.title);
        params.put("i[0]", String.valueOf(registrationData.time.getTime() / 1000L));
        params.put("o[0]", "P");
        params.put("r[0]", "");
        params.put("l[0]", String.valueOf(registrationData.duration));
        params.put("b[0]", registrationData.album);
        params.put("n[0]", "");
        params.put("m[0]", "");
        return executePostRequest(url, params);
    }

    private String[] registerNowPlaying(RegistrationData registrationData, String sessionId, String url) throws IOException {
        Map<String, String> params = new HashMap<String, String>();
        params.put("s", sessionId);
        params.put("a", registrationData.artist);
        params.put("t", registrationData.title);
        params.put("b", registrationData.album);
        params.put("l", String.valueOf(registrationData.duration));
        params.put("n", "");
        params.put("m", "");
        return executePostRequest(url, params);
    }

    private String calculateAuthenticationToken(String password, long timestamp) {
        return DigestUtils.md5Hex(DigestUtils.md5Hex(password) + timestamp);
    }

    private String[] executeGetRequest(String url) throws IOException {
        return executeRequest(new HttpGet(url));
    }

    private String[] executePostRequest(String url, Map<String, String> parameters) throws IOException {
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            params.add(new BasicNameValuePair(entry.getKey(), entry.getValue()));
        }

        HttpPost request = new HttpPost(url);
        request.setEntity(new UrlEncodedFormEntity(params, StringUtil.ENCODING_UTF8));

        return executeRequest(request);
    }

    private String[] executeRequest(HttpUriRequest request) throws IOException {
        HttpClient client = new DefaultHttpClient();
        HttpConnectionParams.setConnectionTimeout(client.getParams(), 15000);
        HttpConnectionParams.setSoTimeout(client.getParams(), 15000);

        try {
            ResponseHandler<String> responseHandler = new BasicResponseHandler();
            String response = client.execute(request, responseHandler);
            return response.split("\\n");

        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    public void setSettingsService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    private class RegistrationThread extends Thread {
        private RegistrationThread() {
            super("AudioScrobbler Registration");
        }

        @Override
        public void run() {
            while (true) {
                RegistrationData registrationData = null;
                try {
                    registrationData = queue.take();
                    scrobble(registrationData);
                } catch (IOException x) {
                    handleNetworkError(registrationData, x);
                } catch (Exception x) {
                    LOG.warn("Error in Last.fm registration.", x);
                }
            }
        }

        private void handleNetworkError(RegistrationData registrationData, IOException x) {
            try {
                queue.put(registrationData);
                LOG.info("Last.fm registration for " + registrationData.title +
                        " encountered network error.  Will try again later. In queue: " + queue.size(), x);
            } catch (InterruptedException e) {
                LOG.error("Failed to reschedule Last.fm registration for " + registrationData.title, e);
            }
            try {
                sleep(15L * 60L * 1000L);  // Wait 15 minutes.
            } catch (InterruptedException e) {
                LOG.error("Failed to sleep after Last.fm registration failure for " + registrationData.title, e);
            }
        }
    }

    private static class RegistrationData {
        private String username;
        private String password;
        private String artist;
        private String album;
        private String title;
        private int duration;
        private Date time;
        public boolean submission;
    }

}