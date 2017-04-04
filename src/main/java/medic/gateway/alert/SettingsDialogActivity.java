package medic.gateway.alert;

import android.app.Activity;
import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static medic.gateway.alert.BuildConfig.IS_MEDIC_FLAVOUR;
import static medic.gateway.alert.GatewayLog.logEvent;
import static medic.gateway.alert.GatewayLog.logException;
import static medic.gateway.alert.GatewayLog.trace;
import static medic.gateway.alert.SimpleJsonClient2.redactUrl;
import static medic.gateway.alert.Utils.includeVersionNameInActivityTitle;
import static medic.gateway.alert.Utils.showSpinner;
import static medic.gateway.alert.Utils.startMainActivity;

@SuppressWarnings({"PMD.GodClass", "PMD.TooManyMethods"})
public class SettingsDialogActivity extends Activity {
	private static final String MEDIC_URL_FORMATTER = "https://gateway:%s@%s.%s.medicmobile.org/api/sms";
	private static final Pattern MEDIC_URL_PARSER = Pattern.compile("https://gateway:([^:]+)@(.+)\\.([^.]+)\\.medicmobile.org/api/sms");

	private boolean hasPreviousSettings;

//> EVENT HANDLERS
	@Override protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		log("Starting...");

		includeVersionNameInActivityTitle(this);

		SettingsStore store = SettingsStore.in(this);
		hasPreviousSettings = store.hasSettings();

		setContentView(IS_MEDIC_FLAVOUR ? R.layout.settings_dialog_medic : R.layout.settings_dialog_generic);

		if(hasPreviousSettings) {
			Settings settings = store.get();

			populateWebappUrlFields(settings.webappUrl);
			check(R.id.cbxEnablePolling, settings.pollingEnabled);
			check(R.id.cbxEnableCdmaCompatMode, settings.cdmaCompatMode);
		} else {
			cancelButton().setVisibility(View.GONE);
		}
	}

//> CUSTOM EVENT HANDLERS
	public void doSave(View view) {
		log("doSave");

		boolean syncEnabled = checked(R.id.cbxEnablePolling);

		if(syncEnabled) {
			boolean hasErrors = requiredFieldsMissing();
			hasErrors |= illegalCharsInTextfields();
			if(hasErrors) return;
		}

		submitButton().setEnabled(false);
		cancelButton().setEnabled(false);

		if(syncEnabled) {
			verifyAndSave();
		} else saveWithoutVerification();
	}

	public void cancelSettingsEdit(View view) {
		log("cancelSettingsEdit");
		backToMessageListsView();
	}

	public void onBackPressed() {
		if(hasPreviousSettings) {
			backToMessageListsView();
		} else {
			super.onBackPressed();
		}
	}

//> PRIVATE HELPERS
	private boolean requiredFieldsMissing() {
		if(IS_MEDIC_FLAVOUR) {
			boolean hasBasicErrors = false;

			if(isBlank(R.id.txtWebappInstanceName)) {
				showError(R.id.txtWebappInstanceName, R.string.errRequired);
				hasBasicErrors = true;
			} else if(!text(R.id.txtWebappInstanceName).matches("^[\\w-_]+(\\.[\\w-_]+)*$")) {
				showError(R.id.txtWebappInstanceName, R.string.errInvalidInstanceName);
				hasBasicErrors = true;
			}

			if(isBlank(R.id.txtWebappPassword)) {
				showError(R.id.txtWebappPassword, R.string.errRequired);
				hasBasicErrors = true;
			}

			return hasBasicErrors;
		} else {
			if(isBlank(R.id.txtWebappUrl)) {
				showError(R.id.txtWebappUrl, R.string.errRequired);
				return true;
			}
			return false;
		}
	}

	private boolean illegalCharsInTextfields() {
		if(!IS_MEDIC_FLAVOUR) return false;

		boolean illegalCharsFound = false;

		if(text(R.id.txtWebappPassword).contains(".*[/#?@].*")) {
			showError(R.id.txtWebappPassword, R.string.errPassword_illegalChar);
			illegalCharsFound = true;
		}

		return illegalCharsFound;
	}

	private String getWebappUrlFromFields() {
		if(IS_MEDIC_FLAVOUR) {
			String instanceName = text(R.id.txtWebappInstanceName);
			String subdomain = spinnerVal(R.id.spnWebappSubdomain);
			String password = text(R.id.txtWebappPassword);
			return String.format(MEDIC_URL_FORMATTER, password, instanceName, subdomain);
		} else return text(R.id.txtWebappUrl);
	}

	private void populateWebappUrlFields(String appUrl) {
		if(IS_MEDIC_FLAVOUR) {
			Matcher m = MEDIC_URL_PARSER.matcher(appUrl);
			if(m.matches()) {
				text(R.id.txtWebappInstanceName, m.group(2));
				spinnerVal(R.id.spnWebappSubdomain, m.group(3));
				text(R.id.txtWebappPassword, m.group(1));
			} else {
				trace(this, "URL not being parsed correctly: %s", redactUrl(appUrl));
			}
		} else text(R.id.txtWebappUrl, appUrl);
	}

	private void backToMessageListsView() {
		startMainActivity(this);
		finish();
	}

	private void verifyAndSave() {
		final String webappUrl = getWebappUrlFromFields();
		final boolean cdmaCompatMode = checked(R.id.cbxEnableCdmaCompatMode);

		final ProgressDialog spinner = showSpinner(this,
				String.format(getString(R.string.txtValidatingWebappUrl),
						redactUrl(webappUrl)));

		new AsyncTask<Void, Void, WebappUrlVerififcation>() {
			protected WebappUrlVerififcation doInBackground(Void..._) {
				return new WebappUrlVerifier(SettingsDialogActivity.this).verify(webappUrl);
			}
			protected void onPostExecute(WebappUrlVerififcation result) {
				boolean savedOk = false;

				if(result.isOk)
					savedOk = saveSettings(new Settings(result.webappUrl, true, cdmaCompatMode));
				else
					showError(IS_MEDIC_FLAVOUR ? R.id.txtWebappInstanceName : R.id.txtWebappUrl, result.failure);

				if(savedOk) startApp();
				else {
					submitButton().setEnabled(true);
					cancelButton().setEnabled(true);
				}
				spinner.dismiss();
			}
		}.execute();
	}

	private void saveWithoutVerification() {
		final String webappUrl = getWebappUrlFromFields();
		final boolean cdmaCompatMode = checked(R.id.cbxEnableCdmaCompatMode);

		final ProgressDialog spinner = showSpinner(this,
				getString(R.string.txtSavingSettings));

		AsyncTask.execute(new Runnable() {
			public void run() {
				boolean savedOk = saveSettings(new Settings(webappUrl, false, cdmaCompatMode));

				if(savedOk) startApp();
				else {
					runOnUiThread(new Runnable() {
						public void run() {
							submitButton().setEnabled(true);
							cancelButton().setEnabled(true);
						}
					});
				}
				spinner.dismiss();
			}
		});
	}

	private boolean saveSettings(Settings s) {
		try {
			SettingsStore.in(this).save(s);
			logEvent(SettingsDialogActivity.this, "Settings saved.  Webapp URL: %s", redactUrl(s.webappUrl));
			return true;
		} catch(final IllegalSettingsException ex) {
			logException(ex, "SettingsDialogActivity.saveSettings()");
			runOnUiThread(new Runnable() {
				public void run() {
					for(IllegalSetting error : ex.errors) {
						showError(error);
					}
				}
			});
			return false;
		} catch(final SettingsException ex) {
			logException(ex, "SettingsDialogActivity.saveSettings()");
			runOnUiThread(new Runnable() {
				public void run() {
					submitButton().setError(ex.getMessage());
				}
			});
			return false;
		}
	}

	private void startApp() {
		startMainActivity(this);
		finish();
	}

	private Button cancelButton() {
		return (Button) findViewById(R.id.btnCancelSettings);
	}

	private Button submitButton() {
		return (Button) findViewById(R.id.btnSaveSettings);
	}

	private boolean checked(int componentId) {
		CheckBox field = (CheckBox) findViewById(componentId);
		return field.isChecked();
	}

	private void check(int componentId, boolean checked) {
		CheckBox field = (CheckBox) findViewById(componentId);
		field.setChecked(checked);
	}

	private String text(int componentId) {
		EditText field = (EditText) findViewById(componentId);
		return field.getText().toString();
	}

	private void text(int componentId, String value) {
		EditText field = (EditText) findViewById(componentId);
		field.setText(value);
	}

	private boolean isBlank(int componentId) {
		return text(componentId).length() == 0;
	}

	private String spinnerVal(int componentId) {
		return ((Spinner) findViewById(componentId)).getSelectedItem().toString();
	}

	private void spinnerVal(int componentId, String val) {
		Spinner spinner = (Spinner) findViewById(componentId);
		for(int i=spinner.getCount()-1; i>=0; --i) {
			if(val.equals(spinner.getItemAtPosition(i).toString())) {
				spinner.setSelection(i);
				return;
			}
		}
	}

	private void showError(IllegalSetting error) {
		showError(error.componentId, error.errorStringId);
	}

	private void showError(int componentId, int stringId) {
		TextView field = (TextView) findViewById(componentId);
		field.setError(getString(stringId));
	}

	private void log(String message, Object... extras) {
		trace(this, message, extras);
	}
}
