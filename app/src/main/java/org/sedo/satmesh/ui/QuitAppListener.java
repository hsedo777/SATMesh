package org.sedo.satmesh.ui;

/**
 * Define the contract of real exiting the app.
 *
 * @author hsedo777
 */
public interface QuitAppListener {

	/**
	 * Stop communication modules/services and quit the App.
	 */
	void quitApp();
}
