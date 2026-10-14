// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import dev.modbench.api.ControlRegistry;
import dev.modbench.api.InputArbiter;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * What a player does without stopping to think, whatever else is going on. Standing in what burns, they step out of it;
 * thrown by a hit toward a drop that would hurt, they hold the keys against the throw and crouch as they land. Ticked
 * before the job every client tick. It takes the controls from whatever holds them, and a job it takes them from ends
 * with its reason (stepped_out_of_fire, held_against_a_hit). A fight is left alone: it does both itself.
 */
final class Hazards {
    private static final Minecraft mc=Minecraft.getMinecraft();
    private static InputArbiter.Lease lease;
    private static int groundY=Integer.MIN_VALUE,thrown=-1,footing;
    private static double lastX,lastZ,tx,tz;
    private static int sinceJolt=99;
    private Hazards(){}
    /** The keys to hold, and the heading to face for them (null: as you face). */
    record Hold(Set<Integer> keys,Float yaw){}

    static void tick(BaritoneNavigation navigation,boolean fighting){
        var me=mc.thePlayer;
        if(me==null||mc.theWorld==null||me.getHealth()<=0||mc.currentScreen!=null){thrown=-1;footing=0;release();return;}
        if(me.onGround)groundY=(int)Math.floor(me.boundingBox.minY+.01);
        // A hit's throw is 0.4 a tick across, seen here after its first tick's ground friction (0.546): a jolt of 0.15 or
        // more that the player's own jump does not explain, with the hurt (counter) within a tick or two of it.
        sinceJolt=Math.hypot(me.motionX-lastX,me.motionZ-lastZ)>=.15&&!me.movementInput.jump?0:sinceJolt+1;lastX=me.motionX;lastZ=me.motionZ;
        if(fighting){release();return;}
        Set<Integer> keys=null;Float yaw=null;String why=null;Hold hold;
        if(hurtsToStand(me)&&(yaw=outOfFire(me))!=null){keys=stepKeys(me,yaw);why="stepped_out_of_fire";}
        else if((hold=counter(me,true))!=null){keys=hold.keys();yaw=hold.yaw();why="held_against_a_hit";}
        if(keys==null){release();return;}
        if(lease==null||!lease.isActive()){
            navigation.cancel(why);var arbiter=ControlRegistry.controls().arbiter();arbiter.revoke(why);
            lease=arbiter.acquire("hazard",r->{});
        }
        if(yaw!=null){me.rotationYaw=yaw;lease.look(yaw,me.rotationPitch);}
        lease.setKeys(keys);
    }
    private static void release(){if(lease!=null){lease.close();lease=null;}}
    /** The keys for a step out on that heading: forward, a jump when the first cell is a step up or you are in a pool, a run when fed enough to. */
    static Set<Integer> stepKeys(EntityPlayer me,float yaw){
        var game=mc.gameSettings;int[] next=cell(me,yaw,1);
        Set<Integer> keys=new LinkedHashSet<>(List.of(game.keyBindForward.getKeyCode()));
        if(me.handleLavaMovement()||!standable(mc.theWorld,next[0],(int)Math.floor(me.boundingBox.minY+.01),next[1]))keys.add(game.keyBindJump.getKeyCode());
        if(me.getFoodStats().getFoodLevel()>6)keys.add(game.keyBindSprint.getKeyCode());
        return keys;
    }

    /** In what burns, on the ground or in it, and not proof against it. */
    static boolean hurtsToStand(EntityPlayer me){
        return (me.onGround||me.handleLavaMovement())&&!me.isImmuneToFire()&&!me.isPotionActive(net.minecraft.potion.Potion.fireResistance)&&burns(mc.theWorld,me.boundingBox);
    }
    /**
     * Thrown by a hit toward a drop of 4 or more, or into what burns, within 3 blocks: the movement keys against the throw
     * until the ground (a plain hit throws you about 2 blocks; air control takes back about 1), then a crouch while still
     * sliding toward it, which the game keeps from walking off an edge. With steer (nothing else holds the view): faced
     * away from the throw and running against it, and once down, walking on until the drop is over 2 blocks off or as far
     * as the one behind, beyond the reach of the next hit's throw. Null when there is nothing to hold against.
     */
    static Hold counter(EntityPlayer me,boolean steer){
        double vx=me.motionX,vz=me.motionZ,v=Math.hypot(vx,vz),ahead;
        if(thrown<0&&sinceJolt<=2&&me.hurtTime>=7&&groundY!=Integer.MIN_VALUE&&v>1e-3&&dropAt(me,vx/v,vz/v)<=3){thrown=0;tx=vx/v;tz=vz/v;}
        if(thrown>=0&&(me.isInWater()||me.handleLavaMovement()||me.isOnLadder()||me.onGround&&thrown>1||thrown>60)){thrown=-1;footing=me.onGround?20:0;}
        if(thrown>=0){thrown++;return v<1e-3?against(me,-tx,-tz,steer,false):against(me,-vx/v,-vz/v,steer,false);}
        boolean sliding=vx*tx+vz*tz>.01;
        if(footing>0&&me.onGround&&(sliding||steer&&(ahead=dropAt(me,tx,tz))<=2&&ahead<dropAt(me,-tx,-tz))){footing--;return against(me,-tx,-tz,steer,sliding);}
        footing=0;return null;
    }
    /** The keys toward (ax, az): facing it at a run with steer, else the nearest of forward, back and the strafes as you face. */
    private static Hold against(EntityPlayer me,double ax,double az,boolean steer,boolean crouch){
        var game=mc.gameSettings;Set<Integer> keys=new LinkedHashSet<>();
        if(crouch)keys.add(game.keyBindSneak.getKeyCode());
        if(steer){
            keys.add(game.keyBindForward.getKeyCode());if(!crouch&&me.getFoodStats().getFoodLevel()>6)keys.add(game.keyBindSprint.getKeyCode());
            return new Hold(keys,(float)Math.toDegrees(Math.atan2(-ax,az)));
        }
        double r=Math.toRadians(me.rotationYaw),forward=ax*-Math.sin(r)+az*Math.cos(r),left=ax*Math.cos(r)+az*Math.sin(r);
        if(forward>.38)keys.add(game.keyBindForward.getKeyCode());else if(forward<-.38)keys.add(game.keyBindBack.getKeyCode());
        if(left>.38)keys.add(game.keyBindLeft.getKeyCode());else if(left<-.38)keys.add(game.keyBindRight.getKeyCode());
        return new Hold(keys,null);
    }
    /** How far along that heading, out to 3 blocks, the floor you stood on ends over a drop of 4 or more, into what burns, or into nothing; infinite if it does not, or ends over less or over water. */
    private static double dropAt(EntityPlayer me,double ux,double uz){
        World w=mc.theWorld;
        for(double d=0;d<=3;d+=.5){
            int x=(int)Math.floor(me.posX+ux*d),z=(int)Math.floor(me.posZ+uz*d);
            if(floor(w,x,groundY-1,z))continue;
            for(int y=groundY-1;y>=Math.max(0,groundY-64);y--){
                var block=w.getBlock(x,y,z);var m=block.getMaterial();
                if(m==net.minecraft.block.material.Material.fire||m==net.minecraft.block.material.Material.lava||block.isBurning(w,x,y,z))return d;
                if(m.isLiquid()||m.blocksMovement())return m.isLiquid()||groundY-(y+1)<4?Double.POSITIVE_INFINITY:d;
            }
            return d;
        }
        return Double.POSITIVE_INFINITY;
    }
    private static boolean floor(World w,int x,int y,int z){return w.getBlock(x,y,z).getMaterial().blocksMovement();}

    /** What the game sets you alight by, in that box: fire or lava, or a block a mod says is burning. */
    static boolean burns(World w,AxisAlignedBB b){
        for(int x=(int)Math.floor(b.minX+.001);x<=(int)Math.floor(b.maxX-.001);x++)for(int y=(int)Math.floor(b.minY+.001);y<=(int)Math.floor(b.maxY-.001);y++)
            for(int z=(int)Math.floor(b.minZ+.001);z<=(int)Math.floor(b.maxZ-.001);z++){
                var block=w.getBlock(x,y,z);var m=block.getMaterial();
                if(m==net.minecraft.block.material.Material.fire||m==net.minecraft.block.material.Material.lava||block.isBurning(w,x,y,z))return true;}
        return false;
    }
    /** A floor under it, room for feet and head, and no fluid. */
    static boolean standable(World w,int x,int y,int z){
        return w.getBlock(x,y-1,z).getMaterial().isSolid()&&!w.getBlock(x,y,z).getMaterial().blocksMovement()&&!w.getBlock(x,y,z).getMaterial().isLiquid()&&!w.getBlock(x,y+1,z).getMaterial().blocksMovement();
    }
    /** The cell (x, z) that far from the player on that heading. */
    static int[] cell(EntityPlayer me,float yaw,double far){
        double r=Math.toRadians(yaw);return new int[]{(int)Math.floor(me.posX-Math.sin(r)*far),(int)Math.floor(me.posZ+Math.cos(r)*far)};
    }
    /**
     * The heading to the nearest place up to 4 blocks off, from straight ahead round to behind, with somewhere to stand and
     * nothing burning, and floor all the way there (fire may be crossed, lava not); or one block up, the step out of a pool.
     * Null if none.
     */
    static Float outOfFire(EntityPlayer me){
        World w=mc.theWorld;int y=(int)Math.floor(me.boundingBox.minY+.01);
        for(int far=1;far<=4;far++)for(float off:new float[]{0,45,-45,90,-90,135,-135,180}){
            float yaw=me.rotationYaw+off;int[] at=cell(me,yaw,far);
            for(int up=0;up<=(far==1?1:0);up++){
                boolean way=true;for(int d=1;d<far&&way;d++){int[] c=cell(me,yaw,d);way=standable(w,c[0],y,c[1]);}
                if(way&&standable(w,at[0],y+up,at[1])&&!burns(w,AxisAlignedBB.getBoundingBox(at[0],y+up,at[1],at[0]+1,y+up+2,at[1]+1)))return yaw;
            }
        }
        return null;
    }
}
