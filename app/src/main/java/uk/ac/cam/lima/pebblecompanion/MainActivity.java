package uk.ac.cam.lima.pebblecompanion;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.support.design.widget.NavigationView;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.MenuItem;
import android.widget.Toast;
import 	android.graphics.Color;

import com.getpebble.android.kit.PebbleKit;
import com.google.android.gms.appindexing.Action;
import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;

import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResult;
import com.google.android.gms.location.LocationSettingsStatusCodes;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import java.util.Set;
import java.util.List;
import java.util.LinkedList;


public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener, OnMapReadyCallback,
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
        LocationListener {

    protected static final int REQUEST_CHECK_SETTINGS = 0x1;
    private static final int MY_PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION = 1;
    // default zoom on google map when auto tracking
    private static final int DEFAULT_ZOOM = 15;
    // frequency of location data updates in milliseconds
    private static final int LOCATION_DATA_FREQUENCY = 500;
    // map on main app display. Needs package access for drawing new hazards in PebbleReceiver
    GoogleMap mMap;
    // google services client
    private GoogleApiClient mGoogleApiClient;
    // last location returned by the GPS
    private Location mLastLocation;
    // request for location data
    private LocationRequest mLocationRequest;
    // is location data being requested
    private boolean mRequestingLocationUpdates;
    // is map centering on user when location changes
    private boolean gpsTracking = false;
    // UI button to centre user on map and track when location changed
    private FloatingActionButton fab_gps;
    // is user on a run, i.e. are we in the 'run loop' - used to toggle button icon
    private boolean running = false;
    // UI button to start run thread
    private FloatingActionButton fab_run;

    private boolean road_works_marker_set = true;
    private boolean pothole_marker_set = true;
    private boolean road_closure_marker_set = true;
    private boolean flooding_marker_set = true;
    private boolean traffic_accident_marker_set = true;
    private boolean broken_glass_marker_set = true;
    private boolean other_marker_set = true;

    private Thread runLoopThread;
    private RunLoop runLoop;
    Handler handler;

    // Called when app initially started.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // set xml layout to view on launch
        setContentView(R.layout.activity_main);

        // create toolbar at top of screen
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Create an instance of GoogleAPIClient.
        if (mGoogleApiClient == null) {
            // ATTENTION: This "addApi(AppIndex.API)"was auto-generated to implement the App Indexing API.
            // See https://g.co/AppIndexing/AndroidStudio for more information.
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .addApi(AppIndex.API).build();
        }

        // add map fragment to screen layout and initialise map
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // request location data and set update frequency
        createLocationRequest();

        // Floating action button to set map to focus on user's location.
        fab_gps = (FloatingActionButton) findViewById(R.id.fab_gps);
        fab_gps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // request location data be turned on in settings if not already
                settingsRequest();
                // if location available, centre map on user with DEFAULT_ZOOM
                if (mLastLocation != null) {
                    // toggle camera centring on user with default zoom.
                    if (!gpsTracking) {
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                                new LatLng(mLastLocation.getLatitude(),
                                        mLastLocation.getLongitude()), DEFAULT_ZOOM));
                        // update button icon.
                        fab_gps.setImageResource(R.drawable.ic_gps_fixed_blue);
                    } else {
                        fab_gps.setImageResource(R.drawable.ic_my_location);
                    }
                    gpsTracking = !gpsTracking;
                }
            }
        });

        // Floating action button to start/stop current run.
        fab_run = (FloatingActionButton) findViewById(R.id.fab_start_run);
        fab_run.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (runLoop == null) return; // location not yet found
                // request location data to be turned on in settings if not already enabled
                settingsRequest();
                if (!running) {
					runLoop.setRunState(RunLoop.RunState.ACTIVE);
                    // update button icon
                    fab_run.setImageResource(R.drawable.ic_action_playback_stop);
                    running = !running;
                } else {
                    fab_run.setImageResource(R.drawable.ic_directions_run_white);
                    running = !running;
                    // update button icon
                    runLoop.setRunState(RunLoop.RunState.INACTIVE);
                    PebbleSender.stopSending();
                    // launch review activity if new hazards recorded
                    if (HazardManager.getNewHazardSet().size() > 0) {
                        Intent openReview = new Intent(fab_run.getContext(), ReviewActivity.class);
                        startActivityForResult(openReview, 0);
                        for (Hazard h : HazardManager.getHazardSet()) {
                            h.setMarker(null);
                        }
                    }
                }
            }
        });

        // create app drawer to be pulled out from left edge of screen
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        PebbleKit.startAppOnPebble(getApplicationContext(), PebbleSender.PEBBLE_APP_UUID);

        handler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if(msg.what == 0){
                    updateMapMarkers();
                }
                super.handleMessage(msg);
            }
        };

        runLoop = new RunLoop(this);
        PebbleSender.startSender(this);
        PebbleReceiver.startReceiver(this, runLoop);

        runLoopThread = new Thread(runLoop);
        runLoopThread.start();
    }

    // Callback method called by system when map loaded and ready.
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
        mMap.getUiSettings().setMapToolbarEnabled(false);
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    MY_PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
            // MY_PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION is an
            // app-defined int constant. The callback method gets the
            // result of the request.
            return;
        }
        // set blue beacon on map representing users location
        mMap.setMyLocationEnabled(true);
        mMap.getUiSettings().setMyLocationButtonEnabled(false);

        // draw hazards from database on map.
        updateMapMarkers();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // permission was granted, do nothing and continue
                } else {
                    // permission denied
                    Toast.makeText(MainActivity.this, "LOCATION_ACCESS Denied", Toast.LENGTH_SHORT)
                            .show();
                }
                return;
            }
        }
    }


    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    // Handle clicks for options within app drawer - change map type, drawer hazard markers
    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        int id = item.getItemId();
        Set<Hazard> hazardSet = HazardManager.getHazardSet();
        switch (id) {
            case R.id.map_satellite:
                if (mMap.getMapType() == GoogleMap.MAP_TYPE_SATELLITE) {
                    mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                } else {
                    mMap.setMapType(GoogleMap.MAP_TYPE_SATELLITE);
                }
                break;
            case R.id.map_terrain:
                if (mMap.getMapType() == GoogleMap.MAP_TYPE_TERRAIN) {
                    mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);
                } else {
                    mMap.setMapType(GoogleMap.MAP_TYPE_TERRAIN);
                }
                break;
            case R.id.road_works_marker:
                if (item.isChecked()) {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Road Works")) {
                            Marker m = h.getMarker();
                            if (m != null) m.remove();
                            h.setMarker(null);
                        }
                    }
                    road_works_marker_set = false;
                } else {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Road Works")) {
                            if (h.getMarker() == null)
                                h.setMarker(mMap.addMarker(new MarkerOptions()
                                        .position(h.getLatLong())
                                        .title(h.getTitle() + ": " + h.getDescription())
                                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_warning_red_24dp))));
                        }
                    }
                    road_works_marker_set = true;
                }
                break;
            case R.id.pothole_marker:
                if (item.isChecked()) {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Pothole")) {
                            Marker m = h.getMarker();
                            if (m != null) m.remove();
                            h.setMarker(null);
                        }
                    }
                    pothole_marker_set = false;
                } else {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Pothole")) {
                            if (h.getMarker() == null)
                                h.setMarker(mMap.addMarker(new MarkerOptions()
                                        .position(h.getLatLong())
                                        .title(h.getTitle() + ": " + h.getDescription())
                                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_move_to_inbox_red_24dp))));
                        }
                    }
                    pothole_marker_set = true;
                }
                break;
            case R.id.road_closure_marker:
                if (item.isChecked()) {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Road Closure")) {
                            Marker m = h.getMarker();
                            if (m != null) m.remove();
                            h.setMarker(null);
                        }
                    }
                    road_closure_marker_set = false;
                } else {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Road Closure")) {
                            if (h.getMarker() == null)
                                h.setMarker(mMap.addMarker(new MarkerOptions()
                                        .position(h.getLatLong())
                                        .title(h.getTitle() + ": " + h.getDescription())
                                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_block_red))));
                        }
                    }
                    road_closure_marker_set = true;
                }
                break;
            case R.id.flooding_marker:
                if (item.isChecked()) {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Flooding")) {
                            Marker m = h.getMarker();
                            if (m != null) m.remove();
                            h.setMarker(null);
                        }
                    }
                    flooding_marker_set = false;
                } else {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Flooding")) {
                            if (h.getMarker() == null)
                                h.setMarker(mMap.addMarker(new MarkerOptions()
                                        .position(h.getLatLong())
                                        .title(h.getTitle() + ": " + h.getDescription())
                                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_pool_red_24dp))));
                        }
                    }
                    flooding_marker_set = true;
                }
                break;
            case R.id.traffic_accident_marker:
                if (item.isChecked()) {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Traffic Accident")) {
                            Marker m = h.getMarker();
                            if (m != null) m.remove();
                            h.setMarker(null);
                        }
                    }
                    traffic_accident_marker_set = false;
                } else {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Traffic Accident")) {
                            if (h.getMarker() == null)
                                h.setMarker(mMap.addMarker(new MarkerOptions()
                                        .position(h.getLatLong())
                                        .title(h.getTitle() + ": " + h.getDescription())
                                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_directions_car_red_24dp))));
                        }
                    }
                    traffic_accident_marker_set = true;
                }
                break;
            case R.id.broken_glass_marker:
                if (item.isChecked()) {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Broken Glass")) {
                            Marker m = h.getMarker();
                            if (m != null) m.remove();
                            h.setMarker(null);
                        }
                    }
                    broken_glass_marker_set = false;
                } else {
                    for (Hazard h : hazardSet) {
                        if (h.getTitle().equals("Broken Glass")) {
                            if (h.getMarker() == null)
                                h.setMarker(mMap.addMarker(new MarkerOptions()
                                        .position(h.getLatLong())
                                        .title(h.getTitle() + ": " + h.getDescription())
                                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_local_bar_red_24dp))));
                        }
                    }
                    broken_glass_marker_set = true;
                }
                break;
            case R.id.other_marker:
                if (item.isChecked()) {
                    for (Hazard h : hazardSet) {
                        if (!h.getTitle().equals("Road Works")
                                && ! h.getTitle().equals("Pothole")
                                && ! h.getTitle().equals("Road Closure")
                                && ! h.getTitle().equals("Flooding")
                                && ! h.getTitle().equals("Traffic Accident")
                                && ! h.getTitle().equals("Broken Glass")) {
                            Marker m = h.getMarker();
                            if (m != null) m.remove();
                            h.setMarker(null);
                        }
                    }
                    other_marker_set = false;
                } else {
                    for (Hazard h : hazardSet) {
                        if (!h.getTitle().equals("Road Works")
                                && ! h.getTitle().equals("Pothole")
                                && ! h.getTitle().equals("Road Closure")
                                && ! h.getTitle().equals("Flooding")
                                && ! h.getTitle().equals("Traffic Accident")
                                && ! h.getTitle().equals("Broken Glass")) {
                            if (h.getMarker() == null)
                                h.setMarker(mMap.addMarker(new MarkerOptions()
                                        .position(h.getLatLong())
                                        .title(h.getTitle() + ": " + h.getDescription())
                                        .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_error_red_24dp))));
                        }
                    }
                    other_marker_set = true;
                }
                break;
        }

        item.setChecked(!item.isChecked());

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    // Initialise a request for high accuracy location data at LOCATION_DATA_FREQUENCY
    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(LOCATION_DATA_FREQUENCY);
        mLocationRequest.setFastestInterval(LOCATION_DATA_FREQUENCY);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    // callbeack method for when system detects location changed
    // updates last known location field.
    @Override
    public void onLocationChanged(Location location) {
        mLastLocation = location;
        if (gpsTracking) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(location.getLatitude(), location.getLongitude()), DEFAULT_ZOOM));
        }
    }

    // called hen app started, connect to google services and hazard database.
    protected void onStart() {
        super.onStart();
        mGoogleApiClient.connect();
        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        Action viewAction = Action.newAction(
                Action.TYPE_VIEW,
                "Main Page",
                Uri.parse("http://host/path"),
                Uri.parse("android-app://uk.ac.cam.lima.pebblecompanion/http/host/path")
        );
        AppIndex.AppIndexApi.start(mGoogleApiClient, viewAction);
    }

    // called when app closes, disconnect from google services
    protected void onStop() {
        super.onStop();
        mGoogleApiClient.disconnect();
        // ATTENTION: This was auto-generated to implement the App Indexing API.
        // See https://g.co/AppIndexing/AndroidStudio for more information.
        Action viewAction = Action.newAction(
                Action.TYPE_VIEW,
                "Main Page",
                Uri.parse("http://host/path"),
                Uri.parse("android-app://uk.ac.cam.lima.pebblecompanion/http/host/path")
        );
        AppIndex.AppIndexApi.end(mGoogleApiClient, viewAction);
    }

    // Called when app leaves foreground
    @Override
    protected void onPause() {
        super.onPause();
        // when app moves to background, stop requesting location updates
        if (mRequestingLocationUpdates) {
            stopLocationUpdates();
        }
    }

    // Called when app regains foreground.
    @Override
    public void onResume() {
        super.onResume();
        // when app gains fore ground, set up google services and request location data
        if (mGoogleApiClient.isConnected()) {
            if (!mRequestingLocationUpdates) {
                startLocationUpdates();
            }
        } else {
            mGoogleApiClient.connect();
        }
        HazardManager.resetNewHazardSet();
    }

    // If permissions available, request location updates from google services
    protected void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    MY_PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
            return;
        }
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, mLocationRequest, this);
    }

    // stop google services providing location data
    protected void stopLocationUpdates() {
        LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, this);
        mRequestingLocationUpdates = false;
    }

    // callback function when connected to google services
    @Override
    public void onConnected(Bundle connectionHint) {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    MY_PERMISSIONS_REQUEST_ACCESS_FINE_LOCATION);
            return;
        }
        // get initial location
        mLastLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        // start requesting periodic location updates
        mRequestingLocationUpdates = true;
        startLocationUpdates();
    }

    @Override
    public void onConnectionSuspended(int cause) {
        // The connection has been interrupted.
        // Disable any UI components that depend on Google APIs
        // until onConnected() is called.
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        // This callback is important for handling errors that
        // may occur while attempting to connect with Google.
        //
        // More about this in the 'Handle Connection Failures' section.
    }

    // check settings for location, if switched off, open user dialog request.
    public void settingsRequest() {
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(mLocationRequest);
        builder.setAlwaysShow(true);

        PendingResult<LocationSettingsResult> result =
                LocationServices.SettingsApi.checkLocationSettings(mGoogleApiClient, builder.build());
        result.setResultCallback(new ResultCallback<LocationSettingsResult>() {
            @Override
            public void onResult(LocationSettingsResult result) {
                final Status status = result.getStatus();
                switch (status.getStatusCode()) {
                    case LocationSettingsStatusCodes.SUCCESS:
                        // All location settings are satisfied. The client can initialize location
                        // requests. Do nothing.
                        break;
                    case LocationSettingsStatusCodes.RESOLUTION_REQUIRED:
                        // Location settings are not satisfied. But could be fixed by showing the user
                        // a dialog.
                        try {
                            // Show the dialog by calling startResolutionForResult(),
                            // and check the result in onActivityResult().
                            status.startResolutionForResult(MainActivity.this, REQUEST_CHECK_SETTINGS);
                        } catch (IntentSender.SendIntentException e) {
                            e.printStackTrace();
                        }
                        break;
                    case LocationSettingsStatusCodes.SETTINGS_CHANGE_UNAVAILABLE:
                        // Location settings are not satisfied. However, we have no way to fix the
                        // settings so we won't show the dialog.
                        break;
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
    // Check for the integer request code originally supplied to startResolutionForResult().
            case REQUEST_CHECK_SETTINGS:
                switch (resultCode) {
                    case Activity.RESULT_OK:
                        startLocationUpdates();
                        break;
                    case Activity.RESULT_CANCELED:
                        // do nothing
                        break;
                }
                break;
        }
    }

    // value determines whether location data is being received
    boolean locationReady() {
        return !(mLastLocation == null);
    }

    // returns current location of user
    LatLng getLocation() {
        // wait until location data available
        while (!locationReady()) {
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                public void run() {
                }
            }, 1000);
        }
        LatLng currLoc = new LatLng(mLastLocation.getLatitude(), mLastLocation.getLongitude());
        return currLoc;
    }

    // draw hazard markers on map.
    private void updateMapMarkers() {
        for (Hazard h : HazardManager.getHazardSet()) {
            if (h.getMarker() != null) continue;
            switch (h.getTitle()) {
                case "Road Works" :
                    if (road_works_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_warning_red_24dp))));
                    }
                    break;
                case "Pothole" :
                    if (pothole_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_move_to_inbox_red_24dp))));
                    }
                    break;
                case "Road Closure" :
                    if (road_closure_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_block_red))));
                    }
                    break;
                case "Flooding" :
                    if (flooding_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_pool_red_24dp))));
                    }
                    break;
                case "Traffic Accident" :
                    if (traffic_accident_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_directions_car_red_24dp))));
                    }
                    break;
                case "Broken Glass" :
                    if (broken_glass_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_local_bar_red_24dp))));
                    }
                    break;
                default:
                    if (other_marker_set) {
                        if (h == null) Log.i("MainAct", "h is null");
                        if (mMap == null) Log.i("MainAct", "mMap is null");
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_error_red_24dp))));
                    }
            }
        }
        for (Hazard h : HazardManager.getNewHazardSet()) {
            if (h.getMarker() != null) continue;
            switch (h.getTitle()) {
                case "Road Works" :
                    if (road_works_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_warning_orange_24dp))));
                    }
                    break;
                case "Pothole" :
                    if (pothole_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_move_to_inbox_orange_24dp))));
                    }
                    break;
                case "Road Closure" :
                    if (road_closure_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_block_orange))));
                    }
                    break;
                case "Flooding" :
                    if (flooding_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_pool_orange_24dp))));
                    }
                    break;
                case "Traffic Accident" :
                    if (traffic_accident_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_directions_car_orange_24dp))));
                    }
                    break;
                case "Broken Glass" :
                    if (broken_glass_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_local_bar_orange_24dp))));
                    }
                    break;
                default:
                    if (other_marker_set) {
                        h.setMarker(mMap.addMarker(new MarkerOptions()
                                .position(h.getLatLong())
                                .title(h.getTitle() + ": " + h.getDescription())
                                .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_error_orange_24dp))));
                    }
            }
        }
    }
}
