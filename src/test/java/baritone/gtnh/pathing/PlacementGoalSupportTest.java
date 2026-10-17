// SPDX-License-Identifier: LGPL-3.0-or-later
package baritone.gtnh.pathing;

import baritone.compat.BlockPos;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlacementGoalSupportTest {
    @Test public void openFloorEgressDoesNotFinishUnderAnAdjacentFloorCell() {
        BlockPos target=new BlockPos(0,66,0);
        // The player under one cell also overlaps its neighbour. Both formerly
        // offered this same low stance as "egress", so no movement or click ran.
        BlockPos low=new BlockPos(0,65,1),onFloor=new BlockPos(0,67,1);
        assertEquals(List.of(onFloor),List.of(low,onFloor).stream().filter(p->PlacementGoalSupport.egress(target,p,false,5)).toList());
        assertFalse(PlacementGoalSupport.egress(new BlockPos(0,66,1),low,false,5));
    }
    @Test public void coveredUpwardPlacementAndScanLimitsRemainAvailable() {
        BlockPos target=new BlockPos(0,66,0);
        assertTrue(PlacementGoalSupport.egress(target,new BlockPos(1,65,0),true,5));
        assertTrue(PlacementGoalSupport.egress(target,new BlockPos(1,71,0),false,5));
        assertFalse(PlacementGoalSupport.egress(target,new BlockPos(1,72,0),false,5));
        assertTrue(PlacementGoalSupport.egress(target,new BlockPos(1,64,0),true,5));
    }
    @Test public void ceilingSupportAllowsReachCheckedUpwardWorkWithoutPillaring() {
        BlockPos target=new BlockPos(60,71,-80),floor=new BlockPos(59,67,-80);
        assertTrue(PlacementGoalSupport.egress(target,floor,true,5));
        assertFalse(PlacementGoalSupport.egress(target,floor,false,5));
        assertFalse(PlacementGoalSupport.egress(target,new BlockPos(59,65,-80),true,5));
        assertFalse(PlacementGoalSupport.egress(target,floor,true,3));
    }
    @Test public void upwardScanFollowsNativeReach() {
        assertEquals(5,PlacementGoalSupport.reachUp(1.54,4.5));
        assertEquals(5,PlacementGoalSupport.reachUp(1.62,8));
        assertEquals(3,PlacementGoalSupport.reachUp(1.54,3));
        assertEquals(1,PlacementGoalSupport.reachUp(1.54,.5));
    }
    @Test public void aViewOnlyFromTheExactCentreIsNotAStance() {
        // Two blocks meeting at an edge let a ray through only along the exact diagonal: the line x-z=const.
        PlacementGoalSupport.Probe seam=(x,z)->x-10.5==z-20.5;
        assertTrue(seam.at(10.5,20.5));
        assertFalse(PlacementGoalSupport.steady(10.5,20.5,seam));
        // A seam along one axis keeps the nudge along it in view; the other nudge leaves it.
        assertFalse(PlacementGoalSupport.steady(10.5,20.5,(x,z)->x==10.5));
        assertFalse(PlacementGoalSupport.steady(10.5,20.5,(x,z)->z==20.5));
        assertTrue(PlacementGoalSupport.steady(10.5,20.5,(x,z)->Math.abs(x-10.5)<.3&&Math.abs(z-20.5)<.3));
        // A stance blocked at its centre costs one probe, as before.
        int[] probes={0};
        assertFalse(PlacementGoalSupport.steady(10.5,20.5,(x,z)->{probes[0]++;return false;}));
        assertEquals(1,probes[0]);
    }
    private static final PlacementGoalSupport.Room OPEN=(column,low,high)->true;
    private static Map<BlockPos,Double> stands(int y,int lo,int hi){
        Map<BlockPos,Double> out=new HashMap<>();
        for(int x=lo;x<=hi;x++)for(int z=lo;z<=hi;z++)out.put(new BlockPos(x,y,z),(double)y);
        return out;
    }
    @Test public void aRoofSeenFromInsideIsNoWalkableSpot() {
        // room_from_middle, 2026-10-17: the floor inside the walls at y1, the top of the built roof at y5. The roof top is a
        // spot level with the next roof cell, and none the player inside can get to.
        Map<BlockPos,Double> spots=stands(1,1,3);spots.putAll(stands(5,0,4));
        Set<BlockPos> walk=PlacementGoalSupport.walkable(spots,new BlockPos(2,1,2),OPEN);
        assertEquals(9,walk.size());
        assertFalse(walk.contains(new BlockPos(2,5,2)));
        // From the roof, the floor is a fall of four: no walk takes it either.
        assertFalse(PlacementGoalSupport.walkable(spots,new BlockPos(2,5,2),OPEN).contains(new BlockPos(2,1,2)));
    }
    @Test public void stairsAndAFallOfThreeAreWalked() {
        Map<BlockPos,Double> spots=new HashMap<>();
        spots.put(new BlockPos(0,1,0),1.0);spots.put(new BlockPos(1,2,0),2.0);spots.put(new BlockPos(2,2,0),2.5);spots.put(new BlockPos(3,3,0),3.5);
        spots.put(new BlockPos(4,0,0),0.5);
        assertEquals(spots.keySet(),PlacementGoalSupport.walkable(spots,new BlockPos(0,1,0),OPEN));
        // Up again from the low end is a rise of three: not without the stairs.
        assertEquals(Set.of(new BlockPos(4,0,0)),PlacementGoalSupport.walkable(Map.of(new BlockPos(4,0,0),0.5,new BlockPos(3,3,0),3.5),new BlockPos(4,0,0),OPEN));
    }
    @Test public void aJumpNeedsHeadroomOverTheSpotItLeaves() {
        Map<BlockPos,Double> spots=Map.of(new BlockPos(0,1,0),1.0,new BlockPos(1,2,0),2.0);
        // A ceiling at y3 over the lower spot: the body fits there (1 to 2.8) but not when it jumps (to 3.8).
        PlacementGoalSupport.Room ceiling=(column,low,high)->!(column.getX()==0&&high>3);
        assertEquals(Set.of(new BlockPos(0,1,0)),PlacementGoalSupport.walkable(spots,new BlockPos(0,1,0),ceiling));
        assertEquals(spots.keySet(),PlacementGoalSupport.walkable(spots,new BlockPos(0,1,0),OPEN));
    }
    @Test public void feetBetweenSpotsStartFromTheOneBelowAndNoneIsNoSpot() {
        Map<BlockPos,Double> spots=stands(1,0,1);
        assertEquals(4,PlacementGoalSupport.walkable(spots,new BlockPos(0,2,0),OPEN).size());
        // Mid-jump over nothing standable: no spot is walkable, which only lets other ways be tried.
        assertTrue(PlacementGoalSupport.walkable(spots,new BlockPos(9,9,9),OPEN).isEmpty());
    }
}
