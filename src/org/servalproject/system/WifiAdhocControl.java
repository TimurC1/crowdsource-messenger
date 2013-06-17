package org.servalproject.system;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.servalproject.R;
import org.servalproject.ServalBatPhoneApplication;
import org.servalproject.shell.CommandLog;
import org.servalproject.shell.Shell;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.preference.PreferenceManager;
import android.util.Log;

public class WifiAdhocControl {
	private final WifiControl control;
	private final ServalBatPhoneApplication app;
	private final ChipsetDetection detection;
	private static final String TAG = "AdhocControl";
	private int state = ADHOC_STATE_DISABLED;
	private WifiAdhocNetwork config;

	public static final String ADHOC_STATE_CHANGED_ACTION = "org.servalproject.ADHOC_STATE_CHANGED_ACTION";
	public static final String EXTRA_SSID = "extra_ssid";
	public static final String EXTRA_STATE = "extra_state";
	public static final String EXTRA_PREVIOUS_STATE = "extra_previous_state";

	public static final int ADHOC_STATE_DISABLED = 0;
	public static final int ADHOC_STATE_ENABLING = 1;
	public static final int ADHOC_STATE_ENABLED = 2;
	public static final int ADHOC_STATE_DISABLING = 3;
	public static final int ADHOC_STATE_ERROR = 4;

	private List<WifiAdhocNetwork> adhocNetworks = new ArrayList<WifiAdhocNetwork>();

	private void readProfiles() {
		String activeProfile = app.settings.getString(
				ADHOC_PROFILE, null);

		int state = ADHOC_STATE_DISABLED;

		// note that properties are reset on boot
		if (isAdhocSupported()
				&& "running".equals(app.coretask.getProp("adhoc.status")))
			state = ADHOC_STATE_ENABLED;

		File prefFolder = new File(this.app.coretask.DATA_FILE_PATH
				+ "/shared_prefs");
		File adhocPrefs[] = prefFolder.listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.getName().startsWith("adhoc_");
			}
		});

		if (adhocPrefs != null) {
			for (int i = 0; i < adhocPrefs.length; i++) {
				String name = adhocPrefs[i].getName();
				if (name.endsWith(".xml"))
					name = name.substring(0, name.indexOf(".xml"));

				WifiAdhocNetwork network = WifiAdhocNetwork.getAdhocNetwork(
						app, name);
				adhocNetworks.add(network);
				if (name.equals(activeProfile)) {
					this.updateState(state, network);
				}
			}
		}
		if (adhocNetworks.isEmpty()) {
			String name = "adhoc_default";
			PreferenceManager.setDefaultValues(app, name, 0,
					R.xml.adhoc_settings, true);

			// copy old defaults / user values?
			SharedPreferences prefs = app.getSharedPreferences(name, 0);
			Editor ed = prefs.edit();
			for (String key : prefs.getAll().keySet()) {
				String value = app.settings.getString(key, null);
				if (value != null)
					ed.putString(key, value);
			}
			ed.commit();

			WifiAdhocNetwork network = WifiAdhocNetwork.getAdhocNetwork(app,
					name);
			adhocNetworks.add(network);
			if (name.equals(activeProfile)) {
				this.updateState(state, network);
			}
		}

		if (config == null)
			this.updateState(ADHOC_STATE_DISABLED, null);
	}

	WifiAdhocControl(WifiControl control) {
		this.control = control;
		this.app = control.app;
		this.detection = ChipsetDetection.getDetection();
	}

	public WifiAdhocNetwork getNetwork(String SSID) {
		if (adhocNetworks.isEmpty())
			readProfiles();

		for (int i = 0; i < adhocNetworks.size(); i++) {
			WifiAdhocNetwork network = adhocNetworks.get(i);
			if (network.getSSID().equals(SSID))
				return network;
		}
		return null;
	}

	public Collection<WifiAdhocNetwork> getNetworks() {
		if (adhocNetworks.isEmpty())
			readProfiles();

		return adhocNetworks;
	}

	public WifiAdhocNetwork getDefaultNetwork() {
		if (adhocNetworks.isEmpty())
			readProfiles();
		if (adhocNetworks.isEmpty())
			return null;
		return adhocNetworks.get(0);
	}

	public static String stateString(Context context, int state) {
		switch (state) {
		case ADHOC_STATE_DISABLED:
			return context.getString(R.string.wifi_disabled);
		case ADHOC_STATE_ENABLING:
			return context.getString(R.string.wifi_enabling);
		case ADHOC_STATE_ENABLED:
			return context.getString(R.string.wifi_enabled);
		case ADHOC_STATE_DISABLING:
			return context.getString(R.string.wifi_disabling);
		}
		return context.getString(R.string.wifi_error);
	}

	public int getState() {
		return this.state;
	}

	public WifiAdhocNetwork getConfig() {
		if (adhocNetworks.isEmpty())
			readProfiles();

		return config;
	}

	static final String ADHOC_PROFILE = "active_adhoc_profile";
	private void updateState(int newState, WifiAdhocNetwork newConfig) {
		int oldState = 0;
		WifiAdhocNetwork oldConfig;

		oldState = this.state;
		oldConfig = this.config;
		this.state = newState;
		this.config = newConfig;

		if (newConfig != null)
			newConfig.setNetworkState(newState);

		if (newConfig != oldConfig && oldConfig != null)
			oldConfig.setNetworkState(ADHOC_STATE_DISABLED);

		Intent modeChanged = new Intent(ADHOC_STATE_CHANGED_ACTION);

		modeChanged.putExtra(EXTRA_SSID,
				config == null ? null : config.getSSID());
		modeChanged.putExtra(EXTRA_STATE, newState);
		modeChanged.putExtra(EXTRA_PREVIOUS_STATE, oldState);

		app.sendStickyBroadcast(modeChanged);
		Editor ed = app.settings.edit();
		ed.putString(ADHOC_PROFILE, newConfig == null ? null
				: newConfig.preferenceName);
		ed.commit();
	}

	private void waitForMode(Shell shell, WifiMode mode, String ipAddr)
			throws IOException {
		String interfaceName = app.coretask.getProp("wifi.interface");
		WifiMode actualMode = null;

		for (int i = 0; i < 50; i++) {
			actualMode = WifiMode.getWiFiMode(shell, interfaceName, ipAddr);

			// We need to allow unknown for wifi drivers that lack linux
			// wireless extensions
			if (actualMode == WifiMode.Adhoc
					|| actualMode == WifiMode.Unknown)
				break;
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Log.e("BatPhone", e.toString(), e);
			}
		}

		Log.v("BatPhone", "iwconfig;\n" + WifiMode.lastIwconfigOutput);

		if (actualMode != mode && actualMode != WifiMode.Unknown) {
			throw new IOException(
					"Failed to control Adhoc mode, mode ended up being '"
							+ actualMode + "'");
		}
	}

	public static boolean isAdhocSupported() {
		return ChipsetDetection.getDetection().isModeSupported(WifiMode.Adhoc);
	}

	synchronized void startAdhoc(Shell shell, WifiAdhocNetwork config)
			throws IOException {

		if (!isAdhocSupported()) {
			updateState(ADHOC_STATE_ERROR, config);
			return;
		}

		updateState(ADHOC_STATE_ENABLING, config);

		try {
			control.logStatus("Updating configuration");
			config.updateConfiguration();

			try {
				control.logStatus("Running adhoc start");
				shell.run(new CommandLog(app.coretask.DATA_FILE_PATH
						+ "/bin/adhoc start 1"));
			} catch (InterruptedException e) {
				IOException x = new IOException();
				x.initCause(e);
				throw x;
			}

			control.logStatus("Waiting for adhoc mode to start");
			waitForMode(shell, WifiMode.Adhoc, config.getNetwork());
			updateState(ADHOC_STATE_ENABLED, config);
		} catch (IOException e) {
			updateState(ADHOC_STATE_ERROR, config);
			throw e;
		}
	}

	synchronized void stopAdhoc(Shell shell) throws IOException {
		if (!isAdhocSupported()) {
			updateState(ADHOC_STATE_ERROR, config);
			return;
		}

		updateState(ADHOC_STATE_DISABLING, this.config);
		try {
			try {
				control.logStatus("Running adhoc stop");
				if (shell.run(new CommandLog(app.coretask.DATA_FILE_PATH
						+ "/bin/adhoc stop 1")) != 0)
					throw new IOException("Failed to stop adhoc mode");
			} catch (InterruptedException e) {
				IOException x = new IOException();
				x.initCause(e);
				throw x;
			}

			control.logStatus("Waiting for wifi to turn off");
			waitForMode(shell, WifiMode.Off,
					config == null ? null : config.getNetwork());
			updateState(ADHOC_STATE_DISABLED, null);
		} catch (IOException e) {
			updateState(ADHOC_STATE_ERROR, this.config);
			throw e;
		}
	}

	private boolean testAdhoc(Chipset chipset, Shell shell) throws IOException,
			UnknownHostException {
		File f = detection.getAdhocAttemptFile(chipset);
		if (f.exists())
			return false;

		detection.setChipset(chipset);
		if (!chipset.supportedModes.contains(WifiMode.Adhoc))
			return false;

		f.createNewFile();

		WifiAdhocNetwork config = WifiAdhocNetwork.getTestNetwork();

		IOException exception = null;

		try {
			startAdhoc(shell, config);
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
			// if starting fails, remember the exception
			exception = e;
		}

		try {
			stopAdhoc(shell);
		} catch (IOException e) {
			// if stopping fails, abort the test completely, and don't try it
			// again
			if (exception != null) {
				Throwable cause = e;
				while (cause.getCause() != null)
					cause = cause.getCause();
				cause.initCause(exception);
			}

			throw e;
		}
		f.delete();

		// fail if starting failed
		return exception == null;
	}

	public boolean testAdhoc(Shell shell, LogOutput log) {
		boolean ret = false;
		log.log("Scanning for known android hardware");

		if (detection.getDetectedChipsets().size() == 0) {
			log.log("Hardware is unknown, scanning for wifi modules");

			detection.inventSupport();
		}

		for (Chipset c : detection.getDetectedChipsets()) {
			log.log("Testing - " + c.chipset);

			try {
				if (testAdhoc(c, shell)) {
					ret = true;
					log.log("Found support for " + c.chipset);
					break;
				}
			} catch (IOException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}

		if (!ret) {
			detection.setChipset(null);
			log.log("No adhoc support found");
		}
		Editor ed = app.settings.edit();
		ed.putString("detectedChipset", ret ? detection.getChipset()
				: "UnKnown");
		ed.commit();

		return ret;
	}
}
