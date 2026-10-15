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
}
