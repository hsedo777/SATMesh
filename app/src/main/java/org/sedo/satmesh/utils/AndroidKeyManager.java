package org.sedo.satmesh.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64; // Use for large compatibility

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class AndroidKeyManager {

	//private static final String TAG = "KeyManager";
	private static final String KEY_ALIAS = "app_cipher_master_key"; // Alias for our AES key in Android Keystore
	private static final String PREF_FILE_NAME = "app_prefs"; // SharedPreferences file, not encrypted
	private static final String ENCRYPTED_APP_CIPHER_KEY = "encrypted_app_cipher_key"; // To store encryption cipher
	private static final String IV_APP_CIPHER_KEY = "iv_app_cipher_key"; // To store encryption IV key

	/**
	 * Get or create the master key from Android KeyStore
	 */
	private static SecretKey getOrCreateKeystoreSecretKey()
			throws NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException, KeyStoreException, CertificateException, IOException, UnrecoverableEntryException {

		KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
		keyStore.load(null); // Load the Keystore

		// Tests if the key exist
		if (!keyStore.containsAlias(KEY_ALIAS)) {
			KeyGenerator keyGenerator = KeyGenerator.getInstance(
					KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
			keyGenerator.init(
					new KeyGenParameterSpec.Builder(KEY_ALIAS,
							KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
							.setBlockModes(KeyProperties.BLOCK_MODE_GCM) // Mode of GCM block
							.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE) // GCM with no padding
							.setKeySize(256) // Key size : 256 bits
							.build());
			keyGenerator.generateKey();
		}

		// Get the key from Keystore
		KeyStore.SecretKeyEntry secretKeyEntry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
		return secretKeyEntry.getSecretKey();
	}

	// Encrypt data with the ket in the Keystore
	private static byte[] encrypt(byte[] data, SecretKey secretKey, SharedPreferences.Editor editor)
			throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException,
			BadPaddingException, IllegalBlockSizeException {

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); // AES and GCM without padding
		cipher.init(Cipher.ENCRYPT_MODE, secretKey);

		byte[] iv = cipher.getIV(); // The IV is randomly generated so it must be store with encrypted data
		byte[] encryptedData = cipher.doFinal(data);

		// Store the IV
		editor.putString(IV_APP_CIPHER_KEY, Base64.encodeToString(iv, Base64.DEFAULT));

		return encryptedData;
	}

	// Decrypt data using the key from the Keystore
	private static byte[] decrypt(byte[] encryptedData, SecretKey secretKey, byte[] iv)
			throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException,
			BadPaddingException, IllegalBlockSizeException, InvalidAlgorithmParameterException {

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		GCMParameterSpec gcmParameterSpec = new GCMParameterSpec(128, iv); // 128 bits for GCM
		cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmParameterSpec);

		return cipher.doFinal(encryptedData);
	}

	/**
	 * Get of create the application cipher passphrase
	 */
	public static byte[] getOrCreateAppCipherPassphrase(Context context) {
		SharedPreferences sharedPrefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE);
		SecretKey keystoreSecretKey;

		try {
			keystoreSecretKey = getOrCreateKeystoreSecretKey();
		} catch (Exception e) {
			throw new RuntimeException("Failed to get or create Keystore key", e);
		}

		String encryptedKeyBase64 = sharedPrefs.getString(ENCRYPTED_APP_CIPHER_KEY, null);
		String ivBase64 = sharedPrefs.getString(IV_APP_CIPHER_KEY, null);
		byte[] appCipherPassphrase;

		SharedPreferences.Editor editor = sharedPrefs.edit();

		if (encryptedKeyBase64 == null || ivBase64 == null) {
			// Generate encryption key
			byte[] rawAppCipherPassphrase = new byte[32]; // 32 B = 256 bits for the cipher
			new SecureRandom().nextBytes(rawAppCipherPassphrase);

			try {
				byte[] encryptedData = encrypt(rawAppCipherPassphrase, keystoreSecretKey, editor);
				editor.putString(ENCRYPTED_APP_CIPHER_KEY, Base64.encodeToString(encryptedData, Base64.DEFAULT));
				editor.apply(); // apply changes
				appCipherPassphrase = rawAppCipherPassphrase;
			} catch (Exception e) {
				throw new RuntimeException("Failed to encrypt and store the app cipher passphrase", e);
			}
		} else {
			try {
				byte[] encryptedData = Base64.decode(encryptedKeyBase64, Base64.DEFAULT);
				byte[] iv = Base64.decode(ivBase64, Base64.DEFAULT);
				appCipherPassphrase = decrypt(encryptedData, keystoreSecretKey, iv);
			} catch (Exception e) {
				throw new RuntimeException("Failed to decrypt the app cipher passphrase. Data might be corrupted.", e);
			}
		}

		return appCipherPassphrase;
	}
}