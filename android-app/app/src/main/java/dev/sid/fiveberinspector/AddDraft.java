package dev.sid.fiveberinspector;

/** Volatile Add flow only. Never serialize credentials or reuse a preview after an edit. */
final class AddDraft {
  String activation = "", confirmation = "";
  boolean form;
  private long revision;
  long revision() { return revision; }
  boolean isCurrent(long captured) { return revision == captured; }
  void changed() { revision++; }
  void acceptCode(String code) {
    activation = code;confirmation = "";form = true;revision++;
  }
  void clear() { activation = "";confirmation = "";revision++; }
}
