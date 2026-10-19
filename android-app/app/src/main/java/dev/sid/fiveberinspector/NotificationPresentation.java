package dev.sid.fiveberinspector;

/** Installation success and operator delivery are separate outcomes. */
final class NotificationPresentation {
  private NotificationPresentation() {}
  static String message(String status) {
    switch (status) {
      case "none": return "No notification attempt recorded.";
      case "delivered": return "Operator acknowledged the notification.";
      case "acknowledged_pending_cleanup": return "Operator acknowledged the notification; local cleanup needs review. Do not reinstall.";
      case "delivery_unknown": return "Delivery could not be confirmed. Contact the operator before retrying; do not reinstall.";
      case "blocked_recipient": return "The notification destination is not supported by this build. Contact the operator; do not reinstall.";
      case "not_started": return "Notification was not sent. Check the connection and contact the operator; do not reinstall.";
      case "unavailable": return "Notification status is unavailable. Contact the operator if service is not ready; do not reinstall.";
      default: return "Notification delivery is not confirmed. Do not reinstall to retry it.";
    }
  }
}
