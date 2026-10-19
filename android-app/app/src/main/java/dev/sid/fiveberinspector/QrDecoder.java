package dev.sid.fiveberinspector;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import java.util.*;

/** Shared local-only decoder for camera frames and imported images. */
final class QrDecoder {
  static String read(LuminanceSource source) throws Exception {
    Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
    hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    MultiFormatReader reader = new MultiFormatReader();
    try {
      return reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints).getText();
    } catch (NotFoundException e) {
      return reader.decode(new BinaryBitmap(new HybridBinarizer(source.invert())), hints).getText();
    }
  }
  private QrDecoder() {}
}
