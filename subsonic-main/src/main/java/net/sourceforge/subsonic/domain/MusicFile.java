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
package net.sourceforge.subsonic.domain;

import static org.apache.commons.lang.StringUtils.getLevenshteinDistance;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.sourceforge.subsonic.Logger;
import net.sourceforge.subsonic.service.MusicFileService;
import net.sourceforge.subsonic.service.ServiceLocator;
import net.sourceforge.subsonic.service.SettingsService;
import net.sourceforge.subsonic.service.metadata.MetaDataParser;
import net.sourceforge.subsonic.util.FileUtil;
import net.sourceforge.subsonic.util.StringUtil;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FalseFileFilter;
import org.apache.commons.io.filefilter.FileFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.springframework.util.StringUtils;

/**
 * Represents a file or directory containing music. Music files can be put in a
 * {@link Playlist}, and may be streamed to remote players. All music files are
 * located in a configurable root music folder.
 * 
 * @author Sindre Mehus
 */
public class MusicFile implements Serializable {

	private static final Logger LOG = Logger.getLogger(MusicFile.class);

	private File file;
	private boolean isFile;
	private boolean isDirectory;
	private boolean isVideo;
	private long lastModified;
	private MetaData metaData;
	private Set<String> excludes;

	/*
	 * Subsonic is very folder-based, supposing folder names represent artist
	 * and album names. Different file systems put various limitations on
	 * allowed character set though, and the folder names might therefore not
	 * match artist name perfectly (Big K.R.I.T. is one example, Songs: Ohia
	 * another). We assume that forbidden characters (trailing dot, semicolon,
	 * etc) have been replaced by an underscore character, and when we encounter
	 * such a folder, we try to fix it searching for files recursively in the
	 * folder, matching appropriate tag (artist/album artist, or album) with the
	 * original folder name. If we find something that's pretty close to the
	 * folder name, we store it here. Otherwise, this will be null.
	 */
	private String substitutionName;

	/**
	 * Do not use this method directly. Instead, use
	 * {@link MusicFileService#getMusicFile}.
	 * 
	 * @param file
	 *            A file on the local file system.
	 * @deprecated Use {@link MusicFileService#getMusicFile} instead.
	 */
	@Deprecated
	public MusicFile(File file) {
		this.file = file;

		// Cache these values for performance.
		isFile = file.isFile();
		isDirectory = file.isDirectory();
		lastModified = file.lastModified();
		String suffix = FilenameUtils.getExtension(file.getName())
				.toLowerCase();
		isVideo = isFile && isVideoFile(suffix);

		// added by MusicCabinet: force reading meta data.
		// we want that cached for faster generation of playlists.
		if (isFile) {
			getMetaData();
		} else {
			setSubstitutionName();
		}
	}

	public void setSubstitutionName() {
		String folderName = file.getName();
		if (folderName.indexOf('_') > -1) {
			try {
				MusicFile firstFile = getFirstChildRecursively();
				if (firstFile != null) {
					MetaData md = firstFile.getMetaData();
					for (String tag : isAlbum() ? Arrays.asList(md.album)
							: Arrays.asList(md.artist, md.albumArtist)) {
						if (tag != null
								&& getLevenshteinDistance(folderName, tag) <= 3) {
							substitutionName = tag;
							return;
						}
					}
				}
			} catch (IOException e) {
				LOG.warn("Could not find a file starting from " + file.getName(), e);
			}
		}
	}

	/**
	 * Empty constructor. Used for testing purposes only.
	 */
	protected MusicFile() {
		isFile = true;
	}

	/**
	 * Returns the underlying {@link File}.
	 * 
	 * @return The file wrapped by this MusicFile.
	 */
	public File getFile() {
		return file;
	}

	/**
	 * Returns whether this music file is a normal file (and not a directory).
	 * 
	 * @return Whether this music file is a normal file (and not a directory).
	 */
	public boolean isFile() {
		return isFile;
	}

	/**
	 * Returns whether this music file is a directory.
	 * 
	 * @return Whether this music file is a directory.
	 */
	public boolean isDirectory() {
		return isDirectory;
	}

	/**
	 * Returns whether this "music" file is a video.
	 * 
	 * @return Whether this "music" file is a video.
	 */
	public boolean isVideo() {
		return isVideo;
	}

	/**
	 * Returns whether this music file is an album, i.e., whether it is a
	 * directory containing songs.
	 * 
	 * @return Whether this music file is an album
	 * @throws IOException
	 *             If an I/O error occurs.
	 */
	public boolean isAlbum() throws IOException {
		if (isFile) {
			return false;
		}
		File[] files = FileUtil.listFiles(file);
		for (File file : files) {
			if (file.isFile()) {
				String suffix = FilenameUtils.getExtension(file.getName())
						.toLowerCase();
				if (isMusicFile(suffix)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns whether this music file is one of the root music folders.
	 * 
	 * @return Whether this music file is one of the root music folders.
	 */
	public boolean isRoot() {
		SettingsService settings = ServiceLocator.getSettingsService();
		List<MusicFolder> folders = settings.getAllMusicFolders();
		for (MusicFolder folder : folders) {
			if (file.equals(folder.getPath())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the time this music file was last modified.
	 * 
	 * @return The time since this music file was last modified, in milliseconds
	 *         since the epoch.
	 */
	public long lastModified() {
		return lastModified;
	}

	/**
	 * Returns the length of the music file. The return value is unspecified if
	 * this music file is a directory.
	 * 
	 * @return The length, in bytes, of the music file, or or <code>0L</code> if
	 *         the file does not exist
	 */
	public long length() {
		return file.length();
	}

	/**
	 * Returns whether this music file exists.
	 * 
	 * @return Whether this music file exists.
	 */
	public boolean exists() {
		return file.exists();
	}

	/**
	 * Returns the name of the music file. This is normally just the last name
	 * in the pathname's name sequence.
	 * 
	 * @return The name of the music file.
	 */
	public String getName() {
		return substitutionName != null ? substitutionName : file.getName();
	}

	/**
	 * Same as {@link #getName}, but without file suffix (unless this music file
	 * represents a directory).
	 * 
	 * @return The name of the file without the suffix
	 */
	public String getNameWithoutSuffix() {
		String name = getName();
		if (isDirectory()) {
			return name;
		}
		int i = name.lastIndexOf('.');
		return i == -1 ? name : name.substring(0, i);
	}

	/**
	 * Returns the file suffix, e.g., "mp3".
	 * 
	 * @return The file suffix.
	 */
	public String getSuffix() {
		return StringUtils.getFilenameExtension(getName());
	}

	/**
	 * Returns the full pathname as a string.
	 * 
	 * @return The full pathname as a string.
	 */
	public String getPath() {
		return file.getPath();
	}

	/**
	 * Returns meta data for this music file.
	 * 
	 * @return Meta data (artist, album, title etc) for this music file.
	 */
	public synchronized MetaData getMetaData() {
		if (metaData == null) {
			MetaDataParser parser = ServiceLocator.getMetaDataParserFactory()
					.getParser(this);
			metaData = (parser == null) ? null : parser.getMetaData(this);
		}
		return metaData;
	}

	/**
	 * Returns the title of the music file, by attempting to parse relevant
	 * meta-data embedded in the file, for instance ID3 tags in MP3 files.
	 * <p/>
	 * If this music file is a directory, or if no tags are found, this method
	 * is equivalent to {@link #getNameWithoutSuffix}.
	 * 
	 * @return The song title of this music file.
	 */
	public String getTitle() {
		return getMetaData() == null ? getNameWithoutSuffix() : getMetaData()
				.getTitle();
	}

	/**
	 * Returns the parent music file.
	 * 
	 * @return The parent music file, or <code>null</code> if no parent exists.
	 * @throws IOException
	 *             If an I/O error occurs.
	 */
	public MusicFile getParent() throws IOException {
		File parent = file.getParentFile();
		return parent == null ? null : createMusicFile(parent);
	}

	/**
	 * Returns all music files that are children of this music file.
	 * 
	 * @param includeFiles
	 *            Whether files should be included in the result.
	 * @param includeDirectories
	 *            Whether directories should be included in the result.
	 * @param sort
	 *            Whether to sort files in the same directory. @return All
	 *            children music files.
	 * @throws IOException
	 *             If an I/O error occurs.
	 */
	public List<MusicFile> getChildren(boolean includeFiles,
			boolean includeDirectories, boolean sort) throws IOException {

		FileFilter filter;
		if (includeFiles && includeDirectories) {
			filter = TrueFileFilter.INSTANCE;
		} else if (includeFiles) {
			filter = FileFileFilter.FILE;
		} else if (includeDirectories) {
			filter = DirectoryFileFilter.DIRECTORY;
		} else {
			filter = FalseFileFilter.INSTANCE;
		}

		File[] children = FileUtil.listFiles(file, filter);
		List<MusicFile> result = new ArrayList<MusicFile>(children.length);

		for (File child : children) {
			try {
				if (acceptMedia(child)) {
					result.add(createMusicFile(child));
				}
			} catch (SecurityException x) {
				LOG.warn("Failed to create MusicFile for " + child, x);
			}
		}
		
		if (sort) {
			Collections.sort(result, new MusicFileSorter());
		}

		return result;
	}

	/**
	 * Returns all music files that are children, grand-children etc of this
	 * music file.
	 * 
	 * @param includeDirectories
	 *            Whether directories should be included in the result.
	 * @param sort
	 *            Whether to sort files in the same directory.
	 * @return All descendant music files.
	 * @throws IOException
	 *             If an I/O error occurs.
	 */
	public List<MusicFile> getDescendants(final boolean includeDirectories,
			final boolean sort) throws IOException {
		final List<MusicFile> result = new ArrayList<MusicFile>();

		Visitor visitor = new Visitor() {
			public void visit(MusicFile musicFile) {
				result.add(musicFile);
			}

			public boolean includeDirectories() {
				return includeDirectories;
			}

			public boolean sorted() {
				return sort;
			}
		};

		accept(visitor);
		return result;
	}

	/**
	 * Accepts the given visitor (as in the <em>Visitor</em> pattern).
	 * Recursively calls <code>accept()</code> on all descendants of this music
	 * file.
	 * 
	 * @param visitor
	 *            The visitor.
	 * @throws IOException
	 *             If an I/O error occurs.
	 */
	public void accept(Visitor visitor) throws IOException {
		if (isFile || visitor.includeDirectories()) {
			visitor.visit(this);
		}

		if (isDirectory()) {
			List<MusicFile> children = getChildren(true, true, visitor.sorted());

			for (MusicFile child : children) {
				child.accept(visitor);
			}
		}
	}

	private MusicFile createMusicFile(File file) {
		return ServiceLocator.getMusicFileService().getMusicFile(file);
	}

	/**
	 * Returns the first direct child (excluding directories). This method is an
	 * optimization.
	 * 
	 * @return The first child, or <code>null</code> if not found.
	 * @throws IOException
	 *             If an I/O error occurs.
	 */
	public MusicFile getFirstChild() throws IOException {
		File[] files = FileUtil.listFiles(file);
		for (File f : files) {
			if (f.isFile() && acceptMedia(f)) {
				try {
					return createMusicFile(f);
				} catch (SecurityException x) {
					LOG.warn("Failed to create MusicFile for " + f, x);
				}
			}
		}
		return null;
	}

	/**
	 * Returns the first direct child (searching directories recursively). This
	 * method is an optimization.
	 * 
	 * @return The first child, or <code>null</code> if not found.
	 * @throws IOException
	 *             If an I/O error occurs.
	 */
	public MusicFile getFirstChildRecursively() throws IOException {
		Deque<File> files = new ArrayDeque<File>();
		files.addFirst(file);
		
		while (files.size() > 0) {
			File currentFile = files.removeFirst();
			if (currentFile.isDirectory()) {
				for (File f : FileUtil.listFiles(currentFile)) {
					files.addFirst(f);
				}
			} else if (acceptMedia(currentFile)) {
				return createMusicFile(currentFile);
			}
		}
		return null;
	}

	private boolean acceptMedia(File file) throws IOException {

		if (isExcluded(file)) {
			return false;
		}

		if (file.isDirectory()) {
			return true;
		}

		String suffix = FilenameUtils.getExtension(file.getName())
				.toLowerCase();
		return isMusicFile(suffix) || isVideoFile(suffix);
	}

	private static boolean isMusicFile(String suffix) {
		for (String s : ServiceLocator.getSettingsService()
				.getMusicFileTypesAsArray()) {
			if (suffix.equals(s.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	private static boolean isVideoFile(String suffix) {
		for (String s : ServiceLocator.getSettingsService()
				.getVideoFileTypesAsArray()) {
			if (suffix.equals(s.toLowerCase())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns whether the given file is excluded, i.e., whether it is listed in
	 * 'subsonic_exclude.txt' in the current directory.
	 * 
	 * @param file
	 *            The child file in question.
	 * @return Whether the child file is excluded.
	 */
	public boolean isExcluded(File file) throws IOException {

		// Exclude all hidden files starting with a "." or "@eaDir" (thumbnail
		// dir created on Synology devices).
		if (file.getName().startsWith(".")
				|| file.getName().startsWith("@eaDir")) {
			return true;
		}

		if (excludes == null) {
			excludes = new HashSet<String>();
			File excludeFile = new File(this.file, "subsonic_exclude.txt");
			if (excludeFile.exists()) {
				String[] lines = StringUtil.readLines(new FileInputStream(
						excludeFile));
				for (String line : lines) {
					excludes.add(line.toLowerCase());
				}
			}
		}

		return excludes.contains(file.getName().toLowerCase());
	}

	/**
	 * Returns whether this music file is equal to another object.
	 * 
	 * @param o
	 *            The object to compare to.
	 * @return Whether this music file is equal to another object.
	 */
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof MusicFile)) {
			return false;
		}

		final MusicFile musicFile = (MusicFile) o;

		if (file != null ? !file.equals(musicFile.file)
				: musicFile.file != null) {
			return false;
		}

		return true;
	}

	/**
	 * Returns the hash code of this music file.
	 * 
	 * @return The hash code of this music file.
	 */
	@Override
	public int hashCode() {
		return (file != null ? file.hashCode() : 0);
	}

	/**
	 * Equivalent to {@link #getPath}.
	 * 
	 * @return This music file as a string.
	 */
	@Override
	public String toString() {
		return getPath();
	}

	/**
	 * Contains meta-data (song title, artist, album etc) for a music file.
	 */
	public static class MetaData implements Serializable {

		private Integer discNumber;
		private Integer trackNumber;
		private String title;
		private String artist;
		private String albumArtist;
		private String album;
		private String genre;
		private String year;
		private Integer bitRate;
		private Boolean variableBitRate;
		private Integer duration;
		private String format;
		private Long fileSize;
		private Integer width;
		private Integer height;

		public Integer getDiscNumber() {
			return discNumber;
		}

		public void setDiscNumber(Integer discNumber) {
			this.discNumber = discNumber;
		}

		public Integer getTrackNumber() {
			return trackNumber;
		}

		public void setTrackNumber(Integer trackNumber) {
			this.trackNumber = trackNumber;
		}

		public String getTitle() {
			return title;
		}

		public void setTitle(String title) {
			this.title = title;
		}

		public String getArtist() {
			return artist;
		}

		public void setArtist(String artist) {
			this.artist = artist;
		}

		public String getAlbumArtist() {
			return albumArtist;
		}

		public void setAlbumArtist(String albumArtist) {
			this.albumArtist = albumArtist;
		}

		public String getAlbum() {
			return album;
		}

		public void setAlbum(String album) {
			this.album = album;
		}

		public String getGenre() {
			return genre;
		}

		public void setGenre(String genre) {
			this.genre = genre;
		}

		public String getYear() {
			return (year == null || year.length() < 4) ?  year : year.substring(0, 4);
		}

		public Integer getYearAsInteger() {
			if (year == null || year.length() < 4) {
				return null;
			}
			try {
				return new Integer(year.substring(0, 4));
			} catch (NumberFormatException x) {
				return null;
			}
		}

		public void setYear(String year) {
			this.year = year;
		}

		public Integer getBitRate() {
			return bitRate;
		}

		public void setBitRate(Integer bitRate) {
			this.bitRate = bitRate;
		}

		public Boolean getVariableBitRate() {
			return variableBitRate;
		}

		public void setVariableBitRate(Boolean variableBitRate) {
			this.variableBitRate = variableBitRate;
		}

		public Integer getDuration() {
			return duration;
		}

		public String getDurationAsString() {
			if (duration == null) {
				return null;
			}

			StringBuffer result = new StringBuffer(8);

			int seconds = duration;

			int hours = seconds / 3600;
			seconds -= hours * 3600;

			int minutes = seconds / 60;
			seconds -= minutes * 60;

			if (hours > 0) {
				result.append(hours).append(':');
				if (minutes < 10) {
					result.append('0');
				}
			}

			result.append(minutes).append(':');
			if (seconds < 10) {
				result.append('0');
			}
			result.append(seconds);

			return result.toString();
		}

		public void setDuration(Integer duration) {
			this.duration = duration;
		}

		public String getFormat() {
			return format;
		}

		public void setFormat(String format) {
			this.format = format;
		}

		public Long getFileSize() {
			return fileSize;
		}

		public void setFileSize(Long fileSize) {
			this.fileSize = fileSize;
		}

		public Integer getWidth() {
			return width;
		}

		public void setWidth(Integer width) {
			this.width = width;
		}

		public Integer getHeight() {
			return height;
		}

		public void setHeight(Integer height) {
			this.height = height;
		}

	}

	/**
	 * Comparator for sorting music files.
	 */
	private static class MusicFileSorter implements Comparator<MusicFile> {

		public int compare(MusicFile a, MusicFile b) {
			if (a.isFile() && b.isDirectory()) {
				return -1;
			}

			if (a.isDirectory() && b.isFile()) {
				return 1;
			}

			if (a.isDirectory() && b.isDirectory()) {
				return a.getName().compareToIgnoreCase(b.getName());
			}
			
			Integer trackA = a.getMetaData() == null ? null : a.getMetaData()
					.getTrackNumber();
			Integer trackB = b.getMetaData() == null ? null : b.getMetaData()
					.getTrackNumber();

			if (trackA == null && trackB != null) {
				return 1;
			}

			if (trackA != null && trackB == null) {
				return -1;
			}

			if (trackA == null && trackB == null) {
				return a.getName().compareToIgnoreCase(b.getName());
			}

			// Compare by disc number, if present.
			Integer discA = a.getMetaData() == null ? null : a.getMetaData()
					.getDiscNumber();
			Integer discB = b.getMetaData() == null ? null : b.getMetaData()
					.getDiscNumber();
			if (discA != null && discB != null) {
				int i = discA.compareTo(discB);
				if (i != 0) {
					return i;
				}
			}

			return trackA.compareTo(trackB);
		}
	}

	/**
	 * Defines a visitor (as in the <em>Visitor</em> pattern), used to traverse
	 * a hierarchy of music files.
	 */
	public static interface Visitor {

		/**
		 * Visits the given music file.
		 * 
		 * @param musicFile
		 *            The music file to visist.
		 */
		void visit(MusicFile musicFile);

		/**
		 * Whether this visitor wants to visit directories.
		 * 
		 * @return Whether this visitor wants to visit directories.
		 */
		boolean includeDirectories();

		/**
		 * Whether this visitor wants to visit files in ascending order (within
		 * a given directory).
		 * 
		 * @return Whether this visitor wants to visit files in ascending order.
		 */
		boolean sorted();
	}
}
