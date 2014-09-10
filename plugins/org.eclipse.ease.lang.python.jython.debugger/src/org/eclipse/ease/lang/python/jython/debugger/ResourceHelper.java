package org.eclipse.ease.lang.python.jython.debugger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.jar.JarFile;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;

public class ResourceHelper {

	/**
	 * Returns an {@link InputStream} for a given resource within a bundle.
	 * 
	 * @param bundle
	 *            qualified name of the bundle to resolve
	 * @param path
	 *            full path of the file to load
	 * @return input stream to resource
	 */
	public static InputStream getResourceStream(final String bundle, final String path) {
		String location = Platform.getBundle(bundle).getLocation();
		try {
			if (location.toLowerCase().endsWith(".jar")) {
				// we need to open a jar file
				final int pos = location.indexOf("file:");
				if (pos != -1) {
					location = location.substring(pos + 5);
					if (!location.startsWith("/")) {
						// relative location, add full path to executable
						location = (Platform.getInstallLocation().getURL().toString() + location).substring(6);
					}

					final JarFile file = new JarFile(location);
					if (path.startsWith("/"))
						return file.getInputStream(file.getEntry(path.substring(1)));
					else
						return file.getInputStream(file.getEntry(path));
				}

			} else {
				final URL url = Platform.getBundle(bundle).getResource(path);
				return FileLocator.resolve(url).openStream();
			}
		} catch (final IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return null;
	}
}
