# SATMesh

SATMesh is a **secure messaging** application that works **without Internet**. It relies on **Bluetooth** and **Wi-Fi** connections to transmit messages—even over long distances—using a **deterministic multi-hop routing** mechanism.

## ✨ Main Features

- Text messaging between Android devices without Internet access
- End-to-end encryption based on the Signal protocol
- Multi-hop message routing to reach distant nodes
- Secure local storage using SQLCipher
- Nearby peer discovery via Nearby Connections
- Smooth and intuitive conversational interface

## 🚀 Technologies Used

- [Java](https://www.oracle.com/java/technologies/javase-downloads.html) – Native Android development
- [Google Nearby Connections](https://developers.google.com/nearby/connections/overview) – Proximity-based communication
- [Signal Protocol](https://signal.org/docs/) – End-to-end encryption
- [SQLite](https://www.sqlite.org/index.html) – Local database
- [SQLCipher](https://www.zetetic.net/sqlcipher/) – Encrypted SQLite storage

## ⚙️ Installation

1. Clone the repository:
   ```bash
   git clone <repository-url>
   ```
2. Open the project in **Android Studio**
3. Let Android Studio automatically install all dependencies
4. Build and run the app on a physical Android device (requires Android 7.0 / API 24 or higher)

## 📱 Usage

- Launch the app on multiple devices
- Grant the necessary permissions
- Discover nearby peers
- Select a detected contact and start a secure conversation

## 🤝 Contributing

Contributions are welcome! To propose changes:
1. Fork the repository
2. Create a new branch (`git checkout -b feature/add-awesome-feature`)
3. Commit your changes (`git commit -am 'Add new awesome feature'`)
4. Push the branch (`git push origin feature/add-awesome-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the [Apache 2.0 License](./LICENSE).

## 🙏 Acknowledgments

- The Signal community for their work on the encryption protocol
- Google for the Nearby libraries

> Built with passion to explore secure decentralized networks on Android.
