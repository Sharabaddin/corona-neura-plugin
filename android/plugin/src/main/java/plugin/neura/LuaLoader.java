package plugin.neura;

//import com.ansca.corona.CoronaActivity;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.ansca.corona.CoronaActivity;
import com.ansca.corona.CoronaEnvironment;
import com.ansca.corona.CoronaLua;
import com.ansca.corona.CoronaRuntime;
import com.ansca.corona.CoronaRuntimeListener;
import com.ansca.corona.CoronaRuntimeTask;
import com.ansca.corona.CoronaRuntimeTaskDispatcher;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.iid.FirebaseInstanceId;
import com.naef.jnlua.JavaFunction;
import com.naef.jnlua.LuaState;
import com.naef.jnlua.NamedJavaFunction;

import com.neura.resources.data.PickerCallback;
import com.neura.resources.device.Capability;
import com.neura.resources.device.DevicesRequestCallback;
import com.neura.resources.device.DevicesResponseData;
import com.neura.resources.insights.DailySummaryCallbacks;
import com.neura.resources.insights.DailySummaryData;
import com.neura.resources.insights.SleepProfileCallbacks;
import com.neura.resources.insights.SleepProfileData;
import com.neura.resources.place.PlaceNode;
import com.neura.resources.situation.SituationCallbacks;
import com.neura.resources.situation.SituationData;
import com.neura.resources.user.UserDetails;
import com.neura.resources.user.UserDetailsCallbacks;
import com.neura.resources.user.UserPhone;
import com.neura.resources.user.UserPhoneCallbacks;
import com.neura.sdk.callbacks.GetPermissionsRequestCallbacks;
import com.neura.sdk.object.AppSubscription;
import com.neura.sdk.object.AuthenticationRequest;
import com.neura.sdk.object.Permission;
import com.neura.sdk.service.GetSubscriptionsCallbacks;
import com.neura.standalonesdk.service.NeuraApiClient;
import com.neura.standalonesdk.util.Builder;
import com.neura.resources.authentication.AuthenticateCallback;
import com.neura.resources.authentication.AuthenticateData;
import com.neura.sdk.service.SubscriptionRequestCallbacks;
import com.neura.sdk.object.EventDefinition;
import com.neura.standalonesdk.util.SDKUtils;


import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;


@SuppressWarnings("WeakerAccess")
public class LuaLoader implements JavaFunction, CoronaRuntimeListener {
	// Neura
	private NeuraApiClient mNeuraApiClient;

	static private int fListener;

	static private CoronaRuntimeTaskDispatcher fDispatcher;

	/** This corresponds to the event name, e.g. [Lua] event.name */
	private static final String PLUGIN_NAME = "neura";

	@SuppressWarnings("unused")
	public LuaLoader() {
		// Initialize member variables.
		fListener = CoronaLua.REFNIL;

		// Set up this plugin to listen for Corona runtime events to be received by methods
		// onLoaded(), onStarted(), onSuspended(), onResumed(), and onExiting().
		CoronaEnvironment.addRuntimeListener(this);
	}

	@Override
	public int invoke(LuaState L) {
		fDispatcher = new CoronaRuntimeTaskDispatcher( L );
		// Register this plugin into Lua with the following functions.
		NamedJavaFunction[] luaFunctions = new NamedJavaFunction[] {
				new ConnectWrapper(),
				new DisconnectWrapper(),
				new AuthenticateWrapper(),
				new AddDeviceWrapper(),
				new AddPlaceWrapper(),
				new EnableAutomaticallySyncLogsWrapper(),
				new EnableNeuraHandingStateAlertMessagesWrapper(),
				new ForgetMeWrapper(),
				new GetAppPermissionsWrapper(),
				new GetDailySummaryWrapper(),
				new GetKnownCapabilitiesWrapper(),
				new GetKnownDevicesWrapper(),
				new GetLocationBasedEventsWrapper(),
				new GetMissingDataForEventWrapper(),
				new GetPermissionStatusWrapper(),
				new GetSdkVersionWrapper(),
				new GetSleepProfileWrapper(),
				new GetSubscriptionsWrapper(),
				new GetUserDetailsWrapper(),
				new GetUserPhoneWrapper(),
				new GetUserPlaceByLabelTypeWrapper(),
				new GetUserSituationWrapper(),
				new HasDeviceWithCapabilityWrapper(),
				new IsLoggedInWrapper(),
				new IsMissingDataForEventWrapper(),
				new RegisterFirebaseTokenWrapper(),
				new RemoveSubscriptionWrapper(),
				new SendFeedbackOnEventWrapper(),
				new ShouldSubscribeToEventWrapper(),
				new SimulateAnEventWrapper(),
				new SubscribeToEventWrapper(),
				new RegisterNotificationForEventWrapper(),
				new UnregisterNotificationForEventWrapper(),

		};
		String libName = L.toString( 1 );
		L.register(libName, luaFunctions);

		// Returning 1 indicates that the Lua require() function will return the above Lua library.
		return 1;
	}

	@Override
	public void onLoaded(CoronaRuntime runtime) {
	}

	@Override
	public void onStarted(CoronaRuntime runtime) {
	}

	@Override
	public void onSuspended(CoronaRuntime runtime) {
	}

	@Override
	public void onResumed(CoronaRuntime runtime) {
	}

	@Override
	public void onExiting(CoronaRuntime runtime) {
		// Remove the Lua listener reference.
		CoronaLua.deleteRef( runtime.getLuaState(), fListener );
		fListener = CoronaLua.REFNIL;
	}

	public static void dispatch(final Map<String, Object> params, final String name, int listener) {
		if (listener == CoronaLua.REFNIL) {
			listener = fListener;
		}
		final int finalListener = listener;

		CoronaRuntimeTask task = new CoronaRuntimeTask() {
			@Override
			public void executeUsing(CoronaRuntime runtime) {
				try {
					LuaState luaState = runtime.getLuaState();
					CoronaLua.newEvent(luaState, name);

					CoronaLua.pushValue(luaState, PLUGIN_NAME);
					luaState.setField(-2, "provider");

					for (String key : params.keySet()) {
						CoronaLua.pushValue(luaState, params.get(key));
						luaState.setField(-2, key);
					}
					if (finalListener > 0) {
						CoronaLua.dispatchEvent(luaState, finalListener, 0);
					}
				} catch (Exception exception) {
					Log.e("Corona", "Unable to dispatch event " + name + " with params: " + params.toString() + ". " + exception.toString());
				}
			}
		};
		fDispatcher.send(task);
	}

	public static void dispatchOnFailure(Bundle bundle, int errorCode, String eventName, int listener){
		HashMap<String, Object> params = new HashMap<>();
		params.put("type", "Failure");
		params.put("isError", true);
		params.put("response", ""+errorCode);
		params.put("data", SDKUtils.errorCodeToString(errorCode));

		dispatch(params, eventName, listener);
	}

	public static Hashtable<Object, Object> jsonToHashTable(LuaState L, String json){
		Hashtable<Object, Object> ht;
		L.getGlobal("require");
		L.pushString("json");
		L.call( 1, 1 );
		L.getField(-1, "decode");
		L.pushString(json);
		L.call( 1, 1 );

		ht = CoronaLua.toHashtable(L, -1);
		L.pop(2);

		return ht;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int connect(LuaState L) {
		if (!L.isTable(1)){
			Log.e("Corona", "neura.connect() takes table as first argument.");
			return 0;
		}
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			fListener = CoronaLua.newRef(L, -1);
		}
		Hashtable<Object, Object> args = CoronaLua.toHashtable(L, 1);
		String appUid = args.get("appUid").toString();
		String appSecret = args.get("appSecret").toString();

		if (appSecret.equals("") || appUid.equals("")){
			Log.e("Corona", "neura.connect() takes table as first argument with appUid and appSecret as required keys.");
			return 0;
		}

		CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
		Builder builder = new Builder(activity.getApplicationContext());
		mNeuraApiClient = builder.build();
		mNeuraApiClient.setAppUid(appUid);
		mNeuraApiClient.setAppSecret(appSecret);
		activity.runOnUiThread(new Runnable() {
			@Override

			public void run() {
				mNeuraApiClient.connect();
			}
		});

		try {
			FirebaseApp.getInstance();
		} catch (IllegalStateException ex) {
			if (args.get("firebase") != null){
				Hashtable<Object, Object> firebaseParams = (Hashtable<Object, Object>)args.get("firebase");
				FirebaseOptions.Builder firebaseBuilder = new FirebaseOptions.Builder();
				firebaseBuilder.setApiKey(firebaseParams.get("apiKey").toString());
				firebaseBuilder.setApplicationId(firebaseParams.get("applicationId").toString());
//				firebaseBuilder.setDatabaseUrl(firebaseParams.get("databaseUrl").toString());
				firebaseBuilder.setGcmSenderId(firebaseParams.get("gcmSenderId").toString());
//				firebaseBuilder.setStorageBucket(firebaseParams.get("storageBucket").toString());


				FirebaseApp.initializeApp(activity, firebaseBuilder.build());
			}

		}

		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int disconnect(LuaState L) {
		mNeuraApiClient.disconnect();

		return 0;
	}


	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int authenticate(final LuaState L) {
		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}
		final int finalListener = listener;
		final CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
		AuthenticationRequest request = new AuthenticationRequest();
		if (L.isTable(1)) {
			Hashtable<Object, Object> args = CoronaLua.toHashtable(L, 1);
			if (args.containsKey("phone")) {
				request.setPhone(args.get("phone").toString());
			}
			if (args.containsKey("appId")) {
				request.setAppId(args.get("appId").toString());
			}
			if (args.containsKey("appSecret")) {
				request.setAppSecret(args.get("appSecret").toString());
			}
		}
//		String str1 = activity.getApplicationContext().getString(activity.getApplicationContext().getApplicationInfo().labelRes);
//		Log.d("Corona", "App name 1: " +str1);
//		String str2 = activity.getString(activity.getApplicationContext().getApplicationInfo().labelRes);
//		Log.d("Corona", "App name 2: " +str2);
//		String str3 = activity.getString(activity.getApplicationInfo().labelRes);
//		Log.d("Corona", "App name 3: " +str3);
		boolean result = mNeuraApiClient.authenticate(request, new AuthenticateCallback() {
			@Override
			public void onSuccess(AuthenticateData authenticateData) {
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Success");
				Hashtable<String, Object> data = new Hashtable<>();
				data.put("neuraUserId", authenticateData.getNeuraUserId());
				data.put("accessToken", authenticateData.getAccessToken());
				ArrayList<EventDefinition> events = authenticateData.getEvents();
				Hashtable<Integer, Object> eventsJson = new Hashtable<Integer, Object>();
				for (EventDefinition event : events){
					Hashtable<Object, Object> ht = jsonToHashTable(L, event.toJson().toString());
					eventsJson.put(eventsJson.size()+1, ht);
				}
				data.put("events", eventsJson);
				params.put("data", data);

				dispatch(params, "authenticate", finalListener);

			}

			@Override
			public void onFailure(int i) {
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Failure");
				params.put("isError", true);
				params.put("response", ""+i);
				params.put("data", SDKUtils.errorCodeToString(i));

				dispatch(params, "authenticate", finalListener);

			}
		});
		L.pushBoolean(result);
		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int subscribeToEvent(LuaState L) {
		if (!L.isString(1) || !L.isString(2)){
			Log.e("Corona", "neura.subscribeToEvent(eventName, eventIdentifier) takes strings as the first two arguments.");
			return 0;
		}

		String eventName = L.toString(1);
		String eventIdentifier = L.toString(2);
		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}


		final int finalListener = listener;
		mNeuraApiClient.subscribeToEvent(eventName, eventIdentifier,
				new SubscriptionRequestCallbacks() {
					@Override
					public void onSuccess(String eventName, Bundle bundle, String eventIdentifier) {
						HashMap<String, Object> params = new HashMap<>();
						params.put("type", "Success");
						Hashtable<String, String> eventData = new Hashtable<String, String>();
						eventData.put("eventName", eventName);
						eventData.put("eventIdentifier", eventIdentifier);
						params.put("event", eventData);

						dispatch(params, "subscribeToEvent", finalListener);
					}

					@Override
					public void onFailure(String eventName, Bundle bundle, int i) {
						HashMap<String, Object> params = new HashMap<>();
						params.put("type", "Failure");
						Hashtable<String, String> eventData = new Hashtable<String, String>();
						eventData.put("eventName", eventName);
						params.put("event", eventData);

						params.put("isError", true);
						params.put("response", ""+i);
						params.put("data", SDKUtils.errorCodeToString(i));

						dispatch(params, "subscribeToEvent", finalListener);
					}
				});

		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int addDevice(LuaState L) {

		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}
		final int finalListener = listener;

		PickerCallback callback = new PickerCallback() {
			@Override
			public void onResult(boolean success) {
				HashMap<String, Object> params = new HashMap<>();
				params.put("response", success);

				dispatch(params, "addDevice", finalListener);
			}
		};

		boolean result = false;
		if (L.isTable(1)){
			Hashtable<Object, Object> params = CoronaLua.toHashtable(L, 1);
			if (params.containsKey("deviceName")){
				mNeuraApiClient.addDevice(params.get("deviceName").toString(), callback);
			}else if(params.containsKey("deviceCapabilityNames")){
				Hashtable<Object, Object> deviceCapabilityNames = (Hashtable<Object, Object>)params.get("deviceCapabilityNames");
				Collection<Object> names = deviceCapabilityNames.values();
				ArrayList<String> namesList = new ArrayList<>();
				for (Object name : names){
					namesList.add(name.toString());
				}
				result = mNeuraApiClient.addDevice(namesList, callback);
			}else{
				result = mNeuraApiClient.addDevice(callback);
			}
		}else{
			result = mNeuraApiClient.addDevice(callback);
		}

		L.pushBoolean(result);
		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int addPlace(LuaState L) {
		// TODO
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int enableAutomaticallySyncLogs(LuaState L) {
		boolean enabled = true;
		if (L.isBoolean(1)){
			enabled = L.toBoolean(1);
		}
		mNeuraApiClient.enableAutomaticallySyncLogs(enabled);
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int enableNeuraHandingStateAlertMessages(LuaState L) {
		boolean enabled = true;
		if (L.isBoolean(1)){
			enabled = L.toBoolean(1);
		}
		mNeuraApiClient.enableAutomaticallySyncLogs(enabled);
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int forgetMe(LuaState L) {
		boolean showAreYouSureDialog = false;
		if (L.isBoolean(1)){
			showAreYouSureDialog = L.toBoolean(1);
		}

		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}
		final int finalListener = listener;
		final CoronaActivity activity = CoronaEnvironment.getCoronaActivity();

		final boolean finalShowAreYouSureDialog = showAreYouSureDialog;
		activity.runOnUiThread(new Runnable() {
			@Override

			public void run() {
				mNeuraApiClient.forgetMe(activity, finalShowAreYouSureDialog, new Handler.Callback() {
					@Override
					public boolean handleMessage(Message msg) {
						HashMap<String, Object> params = new HashMap<>();
						params.put("response", msg.toString());

						dispatch(params, "forgetMe", finalListener);
						return false;
					}
				});
			}
		});

		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getAppPermissions(final LuaState L) {
		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}
		final int finalListener = listener;

		mNeuraApiClient.getAppPermissions(new GetPermissionsRequestCallbacks() {
			@Override
			public void onSuccess(List<Permission> list) throws RemoteException {
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Success");
				Hashtable<Integer, Object> data = new Hashtable<>();
				for (Permission p : list){
					data.put(data.size()+1, jsonToHashTable(L, p.toJson().toString()));
				}
				params.put("data", data);

				dispatch(params, "getAppPermissions", finalListener);
			}

			@Override
			public void onFailure(Bundle bundle, int i) throws RemoteException {
				dispatchOnFailure(bundle, i, "getAppPermissions", finalListener);
			}

			@Override
			public IBinder asBinder() {
				return null;
			}
		});

		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getDailySummary(final LuaState L) {
		if (!L.isNumber(1)){
			Log.e("Corona", "neura.getDailySummary() takes number as the first argument.");
			return 0;
		}

		long timestamp = (long)L.toNumber(1);
		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}

		final int finalListener = listener;

		mNeuraApiClient.getDailySummary(timestamp, new DailySummaryCallbacks() {
			@Override
			public void onSuccess(DailySummaryData situationData) {
				HashMap<String, Object> params = new HashMap<>();
				Log.d("Corona", "Daily Summary : " + situationData.toString());
				params.put("type", "Success");
				Log.d("Corona", situationData.toJson().toString());
				params.put("data", jsonToHashTable(L, situationData.toJson().toString()));

				dispatch(params, "getDailySummary", finalListener);
			}

			@Override
			public void onFailure(Bundle resultData, int errorCode) {
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Failure");
				params.put("isError", true);
				params.put("response", ""+errorCode);
				params.put("data", SDKUtils.errorCodeToString(errorCode));

				dispatch(params, "getDailySummary", finalListener);
			}
		});
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getKnownCapabilities(LuaState L) {
		ArrayList<Capability> list = mNeuraApiClient.getKnownCapabilities();
		Hashtable<Object, Object> capabilities = new Hashtable<>();
		for (Capability c : list){
			capabilities.put(capabilities.size()+1, jsonToHashTable(L, c.toJson().toString()));
		}
		CoronaLua.pushHashtable(L, capabilities);
		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getKnownDevices(final LuaState L) {

		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}

		final int finalListener = listener;

		boolean result = mNeuraApiClient.getKnownDevices(new DevicesRequestCallback() {
			@Override
			public void onSuccess(DevicesResponseData data) {
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Success");
				params.put("data", jsonToHashTable(L, data.toJson().toString()));

				dispatch(params, "getKnownDevices", finalListener);
			}

			@Override
			public void onFailure(int errorCode) {
				dispatchOnFailure(null, errorCode, "getKnownDevices", finalListener);
			}
		});

		L.pushBoolean(result);

		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getLocationBasedEvents(LuaState L) {
		ArrayList<String> list = mNeuraApiClient.getLocationBasedEvents();
		Hashtable<Object, Object> events = new Hashtable<>();
		for (String s : list){
			events.put(events.size()+1, s);
		}
		CoronaLua.pushHashtable(L, events);
		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getMissingDataForEvent(LuaState L) {
		if (!L.isString(1)){
			Log.e("Corona", "neura.getMissingDataForEvent() takes string as the first argument.");
			return 0;
		}

		String eventName = L.toString(1);

		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}

		final int finalListener = listener;

		boolean result = mNeuraApiClient.getMissingDataForEvent(eventName, new PickerCallback() {
			@Override
			public void onResult(boolean success) {
				HashMap<String, Object> params = new HashMap<>();
				params.put("response", success);

				dispatch(params, "getMissingDataForEvent", finalListener);
			}
		});

		L.pushBoolean(result);
		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getPermissionStatus(LuaState L) {

		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getSdkVersion(LuaState L) {
		L.pushString(mNeuraApiClient.getSdkVersion());
		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getSleepProfile(final LuaState L) {
		if (!L.isNumber(1) || !L.isNumber(2)){
			Log.e("Corona", "neura.getSleepProfile() takes numbers as the first two arguments.");
			return 0;
		}

		long startTimestamp = (long)L.toNumber(1);
		long endTimestamp = (long)L.toNumber(2);
		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}

		final int finalListener = listener;

		mNeuraApiClient.getSleepProfile(startTimestamp, endTimestamp, new SleepProfileCallbacks() {
			@Override
			public void onSuccess(SleepProfileData situationData) {
				Log.d("Corona", "Sleep profile : " + situationData.toString());
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Success");
				params.put("data", jsonToHashTable(L, situationData.toJson().toString()));

				dispatch(params, "getSleepProfile", finalListener);
			}

			@Override
			public void onFailure(Bundle resultData, int errorCode) {
				dispatchOnFailure(resultData, errorCode, "getSleepProfile", finalListener);
			}
		});
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getSubscriptions(final LuaState L) {
		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}

		final int finalListener = listener;

		mNeuraApiClient.getSubscriptions(new GetSubscriptionsCallbacks() {
			@Override
			public void onSuccess(List<AppSubscription> list) {
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Success");
				Hashtable<Integer, Object> data = new Hashtable<>();
				for (AppSubscription s : list){
					data.put(data.size()+1, jsonToHashTable(L, s.toJson().toString()));
				}
				params.put("data", data);

				dispatch(params, "getSubscriptions", finalListener);
			}

			@Override
			public void onFailure(Bundle bundle, int i) {
				dispatchOnFailure(bundle, i, "getSubscriptions", finalListener);
			}
		});
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getUserDetails(final LuaState L) {
		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}

		final int finalListener = listener;

		mNeuraApiClient.getUserDetails(new UserDetailsCallbacks() {
			@Override
			public void onSuccess(UserDetails userDetails) {
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Success");
				params.put("data", jsonToHashTable(L, userDetails.toJson().toString()));

				dispatch(params, "getUserDetails", finalListener);
			}

			@Override
			public void onFailure(Bundle resultData, int errorCode) {
				dispatchOnFailure(resultData, errorCode, "getUserDetails", finalListener);
			}
		});
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getUserPhone(LuaState L) {
		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}

		final int finalListener = listener;

		mNeuraApiClient.getUserPhone(new UserPhoneCallbacks() {
			@Override
			public void onSuccess(UserPhone userPhone) {
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Success");
				params.put("data", userPhone.getPhone());

				dispatch(params, "getUserPhone", finalListener);
			}

			@Override
			public void onFailure(Bundle resultData, int errorCode) {
				dispatchOnFailure(resultData, errorCode, "getUserPhone", finalListener);
			}
		});
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getUserPlaceByLabelType(LuaState L) {
		if (!L.isString(1)){
			Log.e("Corona", "neura.getUserPlaceByLabelType() takes string as the first argument.");
			return 0;
		}

		String placeLabelType = L.toString(1);

		ArrayList<PlaceNode> list = mNeuraApiClient.getUserPlaceByLabelType(placeLabelType);
		Hashtable<Object, Object> placeNodes = new Hashtable<>();
		for (PlaceNode p : list){
			placeNodes.put(placeNodes.size()+1, jsonToHashTable(L, p.toJson().toString()));
		}
		CoronaLua.pushHashtable(L, placeNodes);

		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int getUserSituation(final LuaState L) {
		if (!L.isNumber(1)){
			Log.e("Corona", "neura.getUserSituation() takes number as the first argument.");
			return 0;
		}

		long timestamp = (long)L.toNumber(1);

		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}

		final int finalListener = listener;

		mNeuraApiClient.getUserSituation(new SituationCallbacks() {
			@Override
			public void onSuccess(SituationData situationData) {
				Log.d("Corona", "User Situation : " + situationData.toString());
				HashMap<String, Object> params = new HashMap<>();
				params.put("type", "Success");
				params.put("data", jsonToHashTable(L, situationData.toJson().toString()));

				dispatch(params, "getUserSituation", finalListener);
			}

			@Override
			public void onFailure(Bundle resultData, int errorCode) {
				dispatchOnFailure(resultData, errorCode, "getUserSituation", finalListener);
			}
		}, timestamp);

		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int hasDeviceWithCapability(LuaState L) {
		if (!L.isString(1)){
			Log.e("Corona", "neura.hasDeviceWithCapability() takes string as the first argument.");
			return 0;
		}

		String capabilityName = L.toString(1);

		boolean hasCapability = mNeuraApiClient.hasDeviceWithCapability(capabilityName);
		L.pushBoolean(hasCapability);

		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int isLoggedIn(LuaState L) {
		L.pushBoolean(mNeuraApiClient.isLoggedIn());

		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int isMissingDataForEvent(LuaState L) {
		if (!L.isString(1)){
			Log.e("Corona", "neura.isMissingDataForEvent() takes string as the first argument.");
			return 0;
		}

		String eventName = L.toString(1);

		L.pushBoolean(mNeuraApiClient.isMissingDataForEvent(eventName));

		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int registerFirebaseToken(LuaState L) {
		CoronaActivity activity = CoronaEnvironment.getCoronaActivity();
		String firebaseToken = FirebaseInstanceId.getInstance(FirebaseApp.getInstance()).getToken();
		Log.d("Corona", "Firebase token: " + firebaseToken);
		mNeuraApiClient.registerFirebaseToken(activity, firebaseToken);
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int removeSubscription(LuaState L) {
		if (!L.isString(1) || !L.isString(2)){
			Log.e("Corona", "neura.removeSubscription(eventName, eventIdentifier) takes strings as the first two arguments.");
			return 0;
		}

		String eventName = L.toString(1);
		String eventIdentifier = L.toString(2);
		int listener = fListener;
		if ( CoronaLua.isListener( L, -1, "" ) ) {
			listener = CoronaLua.newRef(L, -1);
		}


		final int finalListener = listener;

		mNeuraApiClient.removeSubscription(eventName, eventIdentifier,
				new SubscriptionRequestCallbacks() {
					@Override
					public void onSuccess(String eventName, Bundle bundle, String eventIdentifier) {
						HashMap<String, Object> params = new HashMap<>();
						params.put("type", "Success");
						Hashtable<String, String> eventData = new Hashtable<String, String>();
						eventData.put("eventName", eventName);
						eventData.put("eventIdentifier", eventIdentifier);
						params.put("event", eventData);

//						dispatch(params, "removeSubscription", finalListener);
					}

					@Override
					public void onFailure(String eventName, Bundle bundle, int i) {
						HashMap<String, Object> params = new HashMap<>();
						params.put("type", "Failure");
						Hashtable<String, String> eventData = new Hashtable<String, String>();
						eventData.put("eventName", eventName);
						params.put("event", eventData);

						params.put("isError", true);
						params.put("response", ""+i);
						params.put("data", SDKUtils.errorCodeToString(i));

//						dispatch(params, "removeSubscription", finalListener);
					}

				});

		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int sendFeedbackOnEvent(LuaState L) {
		if (!L.isString(1) || !L.isBoolean(2)){
			Log.e("Corona", "neura.sendFeedbackOnEvent(neuraId, approved) takes string as the first arguments and boolean as the second argument.");
			return 0;
		}
		String neuraId = L.toString(1);
		boolean approved = L.toBoolean(2);
		mNeuraApiClient.sendFeedbackOnEvent(neuraId, approved);
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int shouldSubscribeToEvent(LuaState L) {
		if (!L.isString(1)){
			Log.e("Corona", "neura.shouldSubscribeToEvent(eventName) takes string as the first argument.");
			return 0;
		}
		String eventName = L.toString(1);
		boolean result = mNeuraApiClient.shouldSubscribeToEvent(eventName);
		L.pushBoolean(result);
		return 1;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int simulateAnEvent(LuaState L) {
		mNeuraApiClient.simulateAnEvent();
		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int registerNotificationForEvent(final LuaState L) {
		if ( !L.isString(1) || !L.isTable(2) ) {
			Log.e("Corona", "neura.registerNotificationForEvent(eventName, options) takes string as the first arguments and table as the second argument.");
			return 0;
		}
		String eventName = L.toString(1);
		Hashtable<Object, Object> options = CoronaLua.toHashtable(L, 2);

		String filename = CoronaEnvironment.getCoronaActivity().getFilesDir().getPath() + "/notifications.neura";

		Map<String, Hashtable<Object, Object>> notificationsMap = null;
		try {

			FileInputStream fileInputStream  = new FileInputStream(filename);
			ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);

			notificationsMap = (HashMap<String, Hashtable<Object, Object>>) objectInputStream.readObject();
			objectInputStream.close();

		} catch (IOException | ClassNotFoundException e) {
			Log.d("Corona", "Input Stream : " + e.getMessage());
			e.printStackTrace();
		}

		if (notificationsMap == null){
			notificationsMap = new HashMap<String, Hashtable<Object, Object>>();
		}

		notificationsMap.put(eventName, options);

		try {
			FileOutputStream fileOutputStream = new FileOutputStream(filename);
			ObjectOutputStream objectOutputStream= new ObjectOutputStream(fileOutputStream);

			objectOutputStream.writeObject(notificationsMap);
			objectOutputStream.close();
		} catch (IOException e) {
			Log.d("Corona", "Output Stream : " + e.getMessage());
			e.printStackTrace();
		}

		return 0;
	}

	@SuppressWarnings({"WeakerAccess", "SameReturnValue"})
	public int unregisterNotificationForEvent(final LuaState L) {
		if ( !L.isString(1) ) {
			Log.e("Corona", "neura.unregisterNotificationForEvent(eventName) takes string as the first arguments and table as the second argument.");
			return 0;
		}
		String eventName = L.toString(1);

		String filename = CoronaEnvironment.getCoronaActivity().getFilesDir().getPath() + "/notifications.neura";

		Map<String, Hashtable<Object, Object>> notificationsMap = null;
		try {

			FileInputStream fileInputStream  = new FileInputStream(filename);
			ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);

			notificationsMap = (HashMap<String, Hashtable<Object, Object>>) objectInputStream.readObject();
			objectInputStream.close();

		} catch (IOException | ClassNotFoundException e) {
			Log.d("Corona", "Input Stream : " + e.getMessage());
			e.printStackTrace();
			return 0;
		}

		notificationsMap.remove(eventName);
		try {
			FileOutputStream fileOutputStream = new FileOutputStream(filename);
			ObjectOutputStream objectOutputStream= new ObjectOutputStream(fileOutputStream);

			objectOutputStream.writeObject(notificationsMap);
			objectOutputStream.close();
		} catch (IOException e) {
			Log.d("Corona", "Output Stream : " + e.getMessage());
			e.printStackTrace();
		}

		return 0;
	}

	@SuppressWarnings("unused")
	private class ConnectWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "connect";
		}

		@Override
		public int invoke(LuaState L) {
			return connect(L);
		}
	}

	@SuppressWarnings("unused")
	private class DisconnectWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "disconnect";
		}

		@Override
		public int invoke(LuaState L) {
			return disconnect(L);
		}
	}

	@SuppressWarnings("unused")
	private class AuthenticateWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "authenticate";
		}

		@Override
		public int invoke(LuaState L) {
			return authenticate(L);
		}
	}

	@SuppressWarnings("unused")
	private class SubscribeToEventWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "subscribeToEvent";
		}

		@Override
		public int invoke(LuaState L) {
			return subscribeToEvent(L);
		}
	}

	@SuppressWarnings("unused")
	private class AddDeviceWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "addDevice";
		}

		@Override
		public int invoke(LuaState L) {
			return addDevice(L);
		}
	}

	@SuppressWarnings("unused")
	private class AddPlaceWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "addPlace";
		}

		@Override
		public int invoke(LuaState L) {
			return addPlace(L);
		}
	}

	@SuppressWarnings("unused")
	private class EnableAutomaticallySyncLogsWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "enableAutomaticallySyncLogs";
		}

		@Override
		public int invoke(LuaState L) {
			return enableAutomaticallySyncLogs(L);
		}
	}

	@SuppressWarnings("unused")
	private class EnableNeuraHandingStateAlertMessagesWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "enableNeuraHandingStateAlertMessages";
		}

		@Override
		public int invoke(LuaState L) {
			return enableNeuraHandingStateAlertMessages(L);
		}
	}

	@SuppressWarnings("unused")
	private class ForgetMeWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "forgetMe";
		}

		@Override
		public int invoke(LuaState L) {
			return forgetMe(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetAppPermissionsWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getAppPermissions";
		}

		@Override
		public int invoke(LuaState L) {
			return getAppPermissions(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetDailySummaryWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getDailySummary";
		}

		@Override
		public int invoke(LuaState L) {
			return getDailySummary(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetKnownCapabilitiesWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getKnownCapabilities";
		}

		@Override
		public int invoke(LuaState L) {
			return getKnownCapabilities(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetKnownDevicesWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getKnownDevices";
		}

		@Override
		public int invoke(LuaState L) {
			return getKnownDevices(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetLocationBasedEventsWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getLocationBasedEvents";
		}

		@Override
		public int invoke(LuaState L) {
			return getLocationBasedEvents(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetMissingDataForEventWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getMissingDataForEvent";
		}

		@Override
		public int invoke(LuaState L) {
			return getMissingDataForEvent(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetPermissionStatusWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getPermissionStatus";
		}

		@Override
		public int invoke(LuaState L) {
			return getPermissionStatus(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetSdkVersionWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getSdkVersion";
		}

		@Override
		public int invoke(LuaState L) {
			return getSdkVersion(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetSleepProfileWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getSleepProfile";
		}

		@Override
		public int invoke(LuaState L) {
			return getSleepProfile(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetSubscriptionsWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getSubscriptions";
		}

		@Override
		public int invoke(LuaState L) {
			return getSubscriptions(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetUserDetailsWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getUserDetails";
		}

		@Override
		public int invoke(LuaState L) {
			return getUserDetails(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetUserPhoneWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getUserPhone";
		}

		@Override
		public int invoke(LuaState L) {
			return getUserPhone(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetUserPlaceByLabelTypeWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getUserPlaceByLabelType";
		}

		@Override
		public int invoke(LuaState L) {
			return getUserPlaceByLabelType(L);
		}
	}

	@SuppressWarnings("unused")
	private class GetUserSituationWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "getUserSituation";
		}

		@Override
		public int invoke(LuaState L) {
			return getUserSituation(L);
		}
	}

	@SuppressWarnings("unused")
	private class HasDeviceWithCapabilityWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "hasDeviceWithCapability";
		}

		@Override
		public int invoke(LuaState L) {
			return hasDeviceWithCapability(L);
		}
	}

	@SuppressWarnings("unused")
	private class IsLoggedInWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "isLoggedIn";
		}

		@Override
		public int invoke(LuaState L) {
			return isLoggedIn(L);
		}
	}

	@SuppressWarnings("unused")
	private class IsMissingDataForEventWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "isMissingDataForEvent";
		}

		@Override
		public int invoke(LuaState L) {
			return isMissingDataForEvent(L);
		}
	}

	@SuppressWarnings("unused")
	private class RegisterFirebaseTokenWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "registerFirebaseToken";
		}

		@Override
		public int invoke(LuaState L) {
			return registerFirebaseToken(L);
		}
	}

	@SuppressWarnings("unused")
	private class RemoveSubscriptionWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "removeSubscription";
		}

		@Override
		public int invoke(LuaState L) {
			return removeSubscription(L);
		}
	}

	@SuppressWarnings("unused")
	private class SendFeedbackOnEventWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "sendFeedbackOnEvent";
		}

		@Override
		public int invoke(LuaState L) {
			return sendFeedbackOnEvent(L);
		}
	}

	@SuppressWarnings("unused")
	private class ShouldSubscribeToEventWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "shouldSubscribeToEvent";
		}

		@Override
		public int invoke(LuaState L) {
			return shouldSubscribeToEvent(L);
		}
	}

	@SuppressWarnings("unused")
	private class SimulateAnEventWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "simulateAnEvent";
		}

		@Override
		public int invoke(LuaState L) {
			return simulateAnEvent(L);
		}
	}

	@SuppressWarnings("unused")
	private class RegisterNotificationForEventWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "registerNotificationForEvent";
		}

		@Override
		public int invoke(LuaState L) {
			return registerNotificationForEvent(L);
		}
	}

	@SuppressWarnings("unused")
	private class UnregisterNotificationForEventWrapper implements NamedJavaFunction {

		@Override
		public String getName() {
			return "unregisterNotificationForEvent";
		}

		@Override
		public int invoke(LuaState L) {
			return unregisterNotificationForEvent(L);
		}
	}

}
