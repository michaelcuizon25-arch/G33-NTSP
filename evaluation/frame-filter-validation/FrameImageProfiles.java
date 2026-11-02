import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
class FrameImageProfiles {
 public static void main(String[] args) throws Exception {
  BufferedImage im=ImageIO.read(new File(args[0]));
  for(int y:new int[]{350,500,700,900,1064,1080,1105,1185,1220}) {
   System.out.print("y="+y);
   for(int x=95;x<=180;x+=5) {
    int c=im.getRGB(x,y);
    System.out.print(" "+x+":"+((c>>16)&255)+","+((c>>8)&255)+","+(c&255));
   }
   System.out.println();
  }
 }
}
