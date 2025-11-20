package com.hieptran.android_to_mac_notification_bridge.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.hieptran.android_to_mac_notification_bridge.R;
import com.hieptran.android_to_mac_notification_bridge.data.ConfigRepository;
import com.hieptran.android_to_mac_notification_bridge.service.NotificationBridgeService;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SetupActivity extends AppCompatActivity {

    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private PreviewView previewView;
    private TextView statusText;
    private ConfigRepository configRepository;
    private BarcodeScanner barcodeScanner;
    private ProcessCameraProvider cameraProvider;
    private boolean configCaptured = false;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    statusText.setText(R.string.camera_permission_denied);
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_setup);

        configRepository = new ConfigRepository(this);
        previewView = findViewById(R.id.previewView);
        statusText = findViewById(R.id.textSetupStatus);
        MaterialButton closeButton = findViewById(R.id.buttonCloseSetup);

        closeButton.setOnClickListener(v -> finish());

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                statusText.setText(R.string.camera_setup_error);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases(@NonNull ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        analysis.setAnalyzer(cameraExecutor, this::analyzeImage);

        provider.unbindAll();
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
    }

    @ExperimentalGetImage
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (configCaptured) {
            imageProxy.close();
            return;
        }
        if (imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage inputImage = InputImage.fromMediaImage(
                Objects.requireNonNull(imageProxy.getImage()),
                imageProxy.getImageInfo().getRotationDegrees()
        );
        barcodeScanner.process(inputImage)
                .addOnSuccessListener(this::handleBarcodes)
                .addOnFailureListener(e -> statusText.setText(R.string.barcode_error))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void handleBarcodes(@NonNull List<Barcode> barcodes) {
        if (barcodes.isEmpty()) {
            statusText.setText(R.string.scanning_qr_hint);
            return;
        }
        for (Barcode barcode : barcodes) {
            if (barcode.getRawValue() != null) {
                tryParseConfig(barcode.getRawValue());
                break;
            }
        }
    }

    private void tryParseConfig(@NonNull String rawValue) {
        try {
            JSONObject jsonObject = new JSONObject(rawValue);
            String apiKey = jsonObject.optString("api_key", null);
            String encryptionKey = jsonObject.optString("encryption_key", null);
            if (TextUtils.isEmpty(apiKey) || !ConfigRepository.isValidKey(encryptionKey)) {
                statusText.setText(R.string.invalid_qr_content);
                return;
            }
            configRepository.saveConfig(apiKey, encryptionKey);
            configCaptured = true;
            statusText.setText(R.string.config_saved_success);
            ComponentName componentName = new ComponentName(this, NotificationBridgeService.class);
            NotificationListenerService.requestRebind(componentName);
            finishDelayed();
        } catch (JSONException e) {
            statusText.setText(R.string.invalid_qr_content);
        }
    }

    private void finishDelayed() {
        statusText.postDelayed(this::finish, 1200);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        barcodeScanner.close();
        cameraExecutor.shutdownNow();
    }
}
