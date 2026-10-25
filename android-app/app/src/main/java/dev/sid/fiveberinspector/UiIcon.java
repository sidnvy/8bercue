package dev.sid.fiveberinspector;
import android.graphics.*;import android.graphics.drawable.Drawable;
/** Small authored 24dp navigation icons; native 48dp buttons provide semantics. */
final class UiIcon extends Drawable {
 private final Paint paint=new Paint(3);private final String kind;
 UiIcon(String kind,int color){this.kind=kind;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
 public void draw(Canvas c){c.save();Rect b=getBounds();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);Path p=new Path();
 if(kind.equals("back")){p.moveTo(14,5);p.lineTo(7,12);p.lineTo(14,19);p.moveTo(7,12);p.lineTo(21,12);c.drawPath(p,paint);}
 else if(kind.equals("next")){p.moveTo(9,5);p.lineTo(16,12);p.lineTo(9,19);c.drawPath(p,paint);}
 else if(kind.equals("connection")){c.rotate(-40,12,12);c.drawRoundRect(3,8,14,16,4,4,paint);c.drawRoundRect(10,8,21,16,4,4,paint);}
 else if(kind.equals("appearance")){c.drawCircle(12,12,9,paint);p.moveTo(12,3);p.lineTo(12,21);c.drawPath(p,paint);c.drawArc(6,6,18,18,-90,180,false,paint);}
 else if(kind.equals("records")){c.drawCircle(12,12,9,paint);p.moveTo(12,6);p.lineTo(12,12);p.lineTo(16,14);c.drawPath(p,paint);}
 else if(kind.equals("help")){c.drawCircle(12,12,9,paint);p.moveTo(9,9);p.cubicTo(9,5,16,5,15,10);p.cubicTo(15,12,12,12,12,14);c.drawPath(p,paint);c.drawPoint(12,17,paint);}
 else if(kind.equals("privacy")){p.moveTo(12,2);p.lineTo(21,6);p.lineTo(20,15);p.quadTo(17,20,12,22);p.quadTo(7,20,4,15);p.lineTo(3,6);p.close();c.drawPath(p,paint);c.drawRoundRect(9,10,15,16,1,1,paint);c.drawArc(10,7,14,13,180,180,false,paint);}
 else if(kind.equals("card")){p.moveTo(5,3);p.lineTo(15,3);p.lineTo(20,8);p.lineTo(20,21);p.lineTo(5,21);p.close();c.drawPath(p,paint);c.drawRoundRect(8,10,17,17,1,1,paint);c.drawLine(12,10,12,17,paint);c.drawLine(8,14,17,14,paint);}
 else if(kind.equals("lab")){p.moveTo(9,3);p.lineTo(15,3);p.moveTo(10,3);p.lineTo(10,10);p.lineTo(4,20);p.quadTo(3,22,6,22);p.lineTo(18,22);p.quadTo(21,22,20,20);p.lineTo(14,10);p.lineTo(14,3);p.moveTo(7,16);p.lineTo(17,16);c.drawPath(p,paint);}
 else {float[] xy={9,3,15,3,16,6,19,7,21,12,19,17,16,18,15,21,9,21,8,18,5,17,3,12,5,7,8,6};p.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)p.lineTo(xy[i],xy[i+1]);p.close();c.drawPath(p,paint);c.drawCircle(12,12,3,paint);}c.restore();}
 public void setAlpha(int a){paint.setAlpha(a);}public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
