// SPDX-License-Identifier: LGPL-3.0-or-later
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;

/** Stances must be actionable by the source builder's placement scan. */
public final class PlacementGoalSupport {
    private PlacementGoalSupport() {}
    /** How many cells above the feet the scan looks: the ceiling face over the highest one is within
     * native reach of an eye eyeAboveFeet up, and never past the scan's horizontal radius of 5. */
    public static int reachUp(double eyeAboveFeet,double reach) {
        return Math.max(1,Math.min(5,(int)Math.floor(eyeAboveFeet+reach)-1));
    }
    /** Above the feet only a covered cell is actionable: the ceiling supplies the support. */
    public static boolean actionableHeight(int targetY,int feetY,boolean covered,int up) {
        int dy=targetY-feetY;
        return dy>=-5&&dy<=up&&(dy<=0||covered);
    }
    public static boolean egress(BlockPos target,BlockPos feet,boolean covered,int up) {
        return (feet.getX()!=target.getX()||feet.getZ()!=target.getZ())&&actionableHeight(target.getY(),feet.getY(),covered,up);
    }
    /** Whether the placement can be clicked with the body at x,z. */
    @FunctionalInterface public interface Probe {boolean at(double x,double z);}
    /** How far off a block centre a promised stance must still work: one pixel, well inside the 0.2 a centred body keeps
     * from the cells beside it. */
    public static final double NUDGE=1/16.0;
    /** Whether a stance probed at its block centre is one the player can use. The player comes to rest somewhere in the
     * block, not on its centre, so the click must also work a pixel off it, along x and along z. Two directions, because
     * a view that exists only from the exact centre lies in one plane through the eye (the native tracer passes a ray
     * along an exact diagonal through the seam between two blocks that touch only at an edge), and unless that seam is
     * level with the eye at most one of the two nudges stays in the plane. */
    public static boolean steady(double x,double z,Probe probe) {
        return probe.at(x,z)&&probe.at(x+NUDGE,z)&&probe.at(x,z+NUDGE);
    }
    /** What a walk pays on top of the usual for a scaffold block right under a cell still to fill: about four blocks' placing. */
    public static final double BURIED=80;
    /** Whether a column is open for the body between two heights. */
    @FunctionalInterface public interface Room {boolean clear(BlockPos column,double low,double high);}
    /** What a walk on foot alone takes between standing spots side by side: a jump up, or down at most the walk's fall limit. */
    public static final double RISE=1.25,DROP=3,BODY=1.8;
    /**
     * The standing spots (feet to standing height) the player can walk to from `from` without placing, breaking, parkour,
     * ladders or water. Something missed here is a spot the path search may still find a way to, so it only decides which
     * way is tried first, never what is possible.
     */
    public static Set<BlockPos> walkable(Map<BlockPos,Double> stands,BlockPos from,Room room) {
        Set<BlockPos> out=new HashSet<>();Deque<BlockPos> todo=new ArrayDeque<>();
        for(BlockPos start:List.of(from,from.down(),from.up())){
            Double y=stands.get(start);
            if(y!=null&&room.clear(start,y,y+BODY)){out.add(start);todo.add(start);break;}
        }
        int[][] sides={{1,0},{-1,0},{0,1},{0,-1}};
        while(!todo.isEmpty()){
            BlockPos at=todo.poll();double y=stands.get(at);
            for(int[] side:sides)for(int dy=2;dy>=-4;dy--){
                BlockPos to=new BlockPos(at.getX()+side[0],at.getY()+dy,at.getZ()+side[1]);Double toY=stands.get(to);
                if(toY==null||out.contains(to)||toY-y>RISE||y-toY>DROP)continue;
                // The body passes the target column from the higher of the two heights, and jumps clear of the spot it leaves.
                if(!room.clear(to,toY,Math.max(y,toY)+BODY)||toY>y&&!room.clear(at,y,toY+BODY))continue;
                out.add(to);todo.add(to);
            }
        }
        return out;
    }
}
