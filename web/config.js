/*
 * Where the backend runs. The ONLY setting the web pages need.
 *   Testing on your computer:  "http://localhost:8080"
 *   Real use:                  "https://your-backend-address"
 * No secret keys go in this file: everyone can read it.
 *
 * openHours (optional): when the Xerox center's own PC is the server, it is only there while the
 * center is open. The pages then say "the Xerox center's computer is not on right now" with these
 * words behind it, e.g. openHours: "9:30 AM to 6 PM, Monday to Saturday".
 *
 * A server that has moved says so itself (MOVED_TO on the old server): devices that open this site
 * then go to the new address and remember it, even before this file is changed.
 */
window.CONFIG = {
  apiBase: "https://campus-print-backend.onrender.com"
};
