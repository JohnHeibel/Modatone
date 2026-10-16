// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class FightJobTest {
    @Test public void aMobWentOffOnlyWhereAnExplosionWas(){
        var blasts=List.of(new double[]{10,64,10,100},new double[]{40,70,-5,101});
        assertTrue(FightJob.wentOffAt(new double[]{10,64,10},blasts));
        assertTrue(FightJob.wentOffAt(new double[]{41.5,71,-4},blasts)); // drawn a little behind where the server had it
        assertFalse(FightJob.wentOffAt(new double[]{14,64,10},blasts));   // gone four blocks off: killed or despawned, not the blast
        assertFalse(FightJob.wentOffAt(new double[]{10,64,10},List.of()));
    }
    @Test public void aMobIsSwungAtAgainOnceItsHurtTimerIsDownToHalf(){
        assertEquals(10,FightJob.guard(0,20)); // most mobs: hurt again 10 ticks after a hit, however fast the swing
        assertEquals(8,FightJob.guard(0,15));  // the timer is let through at 7.5 or less, so an odd one rounds up
        assertEquals(0,FightJob.guard(0,0));   // one with no guard of its own, every tick
        assertEquals(20,FightJob.guard(20,20)); // a slower intervalTicks wins
    }
}
