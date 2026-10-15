// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.ForgePlanningTestRunner;
import dev.modbench.api.InputArbiter;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(ForgePlanningTestRunner.class)
public class HazardsTest {
    @Test public void explicitRecoveryOwnsInputOnlyForItsLease(){
        var arbiter=new InputArbiter(new InputArbiter.Sink(){
            public void applyKeys(Set<Integer> keys){}
            public void applyLook(float yaw,float pitch){}
        });
        arbiter.acquire("baritone_mine",reason->{});
        assertFalse(Hazards.explicitRecovery(arbiter.current().label()));
        for(String owner:new String[]{"direct","interaction:use_item"}){
            try(var recovery=arbiter.acquire(owner,reason->{})){
                assertTrue(Hazards.explicitRecovery(arbiter.current().label()));
            }
            assertFalse(Hazards.explicitRecovery(arbiter.current().label()));
        }
        arbiter.acquire("hazard",reason->{});
        assertFalse(Hazards.explicitRecovery(arbiter.current().label()));
    }
}
