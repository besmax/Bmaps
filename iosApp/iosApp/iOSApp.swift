import SwiftUI
import Shared
import UIKit

final class BmapsAppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MainViewControllerKt.initializeDownloadScheduling()
        return true
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(BmapsAppDelegate.self) var appDelegate
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
