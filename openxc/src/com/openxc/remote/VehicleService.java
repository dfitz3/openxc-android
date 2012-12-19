package com.openxc.remote;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.openxc.DataPipeline;
import com.openxc.interfaces.VehicleInterface;
import com.openxc.sinks.DataSinkException;
import com.openxc.sinks.RemoteCallbackSink;
import com.openxc.sinks.VehicleDataSink;
import com.openxc.sources.ApplicationSource;
import com.openxc.sources.DataSourceException;
import com.openxc.sources.VehicleDataSource;
import com.openxc.sources.usb.UsbVehicleDataSource;

/**
 * The VehicleService is the centralized source of all vehicle data.
 *
 * To minimize overhead, only one object connects to the current vehicle data
 * source (e.g. a CAN translator or trace file being played back) and all
 * application requests are eventually propagated back to this service.
 *
 * Applications should not use this service directly, but should bind to the
 * in-process {@link com.openxc.VehicleManager} instead - that has an interface
 * that respects Measurement types. The interface used for the
 * VehicleService is purposefully primative as there are a small set of
 * objects that can be natively marshalled through an AIDL interface.
 *
 * By default, the only source of vehicle data is an OpenXC USB device. Other
 * data sources can be instantiated by applications and given the
 * VehicleService as their callback - data will flow backwards from the
 * application process to the remote service and be indistinguishable from local
 * data sources.
 *
 * This service uses the same {@link com.openxc.DataPipeline} as the
 * {@link com.openxc.VehicleManager} to move data from sources to sinks, but it
 * the pipeline is not modifiable by the application as there is no good way to
 * pass running sources through the AIDL interface. The same style is used here
 * for clarity and in order to share code.
 */
public class VehicleService extends Service {
    private final static String TAG = "VehicleService";

    private DataPipeline mPipeline;
    private RemoteCallbackSink mNotifier;
    private ApplicationSource mApplicationSource;
    private UsbVehicleDataSource mUsbDevice;
    private CopyOnWriteArrayList<VehicleInterface> mInterfaces;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Service starting");
        mPipeline = new DataPipeline();
        mApplicationSource = new ApplicationSource();
        mInterfaces = new CopyOnWriteArrayList<VehicleInterface>();
    }

    /**
     * Shut down any associated services when this service is about to die.
     *
     * This stops the data source (e.g. stops trace playback) and kills the
     * thread used for notifying measurement listeners.
     */
    @Override
    public void onDestroy() {
        Log.i(TAG, "Service being destroyed");
        if(mPipeline != null) {
            mPipeline.stop();
        }
    }

    /**
     * Initialize the service and data source when a client binds to us.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "Service binding in response to " + intent);

        initializeDefaultSources();
        initializeDefaultSinks(mPipeline);
        return mBinder;
    }

    /**
     * Reset the data source to the default when all clients disconnect.
     *
     * Since normal service users that want the default (i.e. USB device) don't
     * usually set a new data source, they get could stuck in a situation where
     * a trace file is being used if we don't reset it.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        initializeDefaultSources();
        return false;
    }

    private void initializeDefaultSinks(DataPipeline pipeline) {
        mNotifier = new RemoteCallbackSink();
        pipeline.addSink(mNotifier);
    }

    private void initializeDefaultSources() {
        mPipeline.clearSources();
        mPipeline.addSource(mApplicationSource);

        try {
            mUsbDevice = new UsbVehicleDataSource(this);
            mPipeline.addSource(mUsbDevice);
            mInterfaces.add(mUsbDevice);
        } catch(DataSourceException e) {
            Log.w(TAG, "Unable to add default USB data source", e);
        }
    }

    private final VehicleServiceInterface.Stub mBinder =
        new VehicleServiceInterface.Stub() {
            public RawMeasurement get(String measurementId) {
                return mPipeline.get(measurementId);
            }

            public boolean send(RawMeasurement command) {
                boolean sent = false;
                for(VehicleInterface vehicleInterface : mInterfaces) {
                    try {
                        if(vehicleInterface.receive(command)) {
                            Log.d(TAG, "Sent " + command + " using interface " +
                                    vehicleInterface);
                            sent = true;
                            break;
                        }
                    } catch(DataSinkException e) {
                        continue;
                    }
                }

                if(!sent) {
                    Log.d(TAG, "No interfaces able to send " + command);
                }
                return sent;
            }

            public void receive(RawMeasurement measurement) {
                mApplicationSource.handleMessage(measurement);
            }

            public void register(VehicleServiceListener listener) {
                Log.i(TAG, "Adding listener " + listener);
                mNotifier.register(listener);
            }

            public void unregister(VehicleServiceListener listener) {
                Log.i(TAG, "Removing listener " + listener);
                mNotifier.unregister(listener);
            }

            public void initializeDefaultSources() {
                VehicleService.this.initializeDefaultSources();
            }

            public void clearSources() {
                mPipeline.clearSources();
                // the application source is a bit special and always needs to
                // be there, otherwise an application developer will never be
                // able to remove the USB source but still add their own source.
                mPipeline.addSource(mApplicationSource);
            }

            public int getMessageCount() {
                return VehicleService.this.getMessageCount();
            }

            public List<String> getSourceSummaries() {
                ArrayList<String> sources = new ArrayList<String>();
                for(VehicleDataSource source : mPipeline.getSources()) {
                    sources.add(source.toString());
                }
                return sources;
            }

            public List<String> getSinkSummaries() {
                ArrayList<String> sinks = new ArrayList<String>();
                for(VehicleDataSink sink : mPipeline.getSinks()) {
                    sinks.add(sink.toString());
                }
                return sinks;
            }
    };

    private int getMessageCount() {
        return mPipeline.getMessageCount();
    }

}
