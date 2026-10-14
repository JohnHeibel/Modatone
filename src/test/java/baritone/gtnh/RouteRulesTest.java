// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
package baritone.gtnh;

import baritone.Planning;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.utils.BetterBlockPos;
import baritone.compat.Blocks;
import java.util.*;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** The model's route rules price and forbid cells in the search, and the old built-in costs are rules with their old values. */
@org.junit.runner.RunWith(baritone.ForgePlanningTestRunner.class)
public class RouteRulesTest {
    @BeforeClass public static void bootstrap(){Planning.bootstrap();}
    @After public void restore(){
        List<String> mine=RouteRules.rules().stream().map(RouteRules.Rule::name).filter(n->!RouteRules.defaults().stream().anyMatch(d->d.name().equals(n))).toList();
        if(!mine.isEmpty())call("remove","names",mine);
        call("reset");RouteRules.resetApplied();
    }
    private static Map<String,Object> call(Object... kv){
        Map<String,Object> params=new HashMap<>();params.put("operation",kv[0]);
        for(int i=1;i<kv.length;i+=2)params.put((String)kv[i],kv[i+1]);
        return RouteRules.call(null,params);
    }
    private static Map<String,Object> rule(Object... kv){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]);return m;}
    private static void set(Map<String,Object> rule){call("set","rules",List.of(rule));}
    /** A pit ten deep along z=1, x=2..8, beside the straight way from (0,64,0) to (10,64,0). */
    private static Planning.Terrain pitBeside(){
        var t=new Planning.Terrain();
        for(int x=2;x<=8;x++)for(int y=54;y<64;y++)t.set(x,y,1,Blocks.AIR,0);
        return t;
    }
    private static List<BetterBlockPos> route(Planning.Terrain t,int x,int z){
        return Planning.path(Planning.context(t),new BetterBlockPos(0,64,0),new GoalBlock(x,64,z)).positions();
    }

    @Test public void theBuiltInsAreTheOldCostsWithTheirOldValues(){
        var rules=RouteRules.rules();
        assertEquals(List.of("mobs","spawners","underwater","current_to_harm"),rules.stream().map(RouteRules.Rule::name).toList());
        var on=RouteRules.search(null,false,null);
        assertEquals(10,on.apply(10,0,64,0,false,()->false),1e-9);
        assertEquals(14,on.apply(10,0,64,0,true,()->false),1e-9);   // submergedPenalty 4
        assertEquals(34,on.apply(10,0,64,0,true,()->true),1e-9);    // + currentHazardPenalty 20
        assertFalse("mobs and spawners start off, as avoidance did",rules.get(0).enabled()||rules.get(1).enabled());
        assertEquals(1.5,rules.get(0).factor(),0);assertEquals(2,rules.get(1).factor(),0);
    }
    @Test public void addsAddUpAndFactorsMultiply(){
        set(rule("name","twice","where",rule("box",rule("min",List.of(0,60,0),"max",List.of(4,70,4))),"factor",2));
        set(rule("name","plus3","where",rule("sphere",rule("center",List.of(0,64,0),"radius",3)),"add",3));
        var s=RouteRules.search(null,false,null);
        assertEquals((10+3)*2,s.apply(10,1,64,1,false,()->false),1e-9);
        assertEquals(10*2,s.apply(10,4,64,4,false,()->false),1e-9);      // in the box, outside the sphere
        assertEquals(10+3,s.apply(10,-2,64,0,false,()->false),1e-9);     // in the sphere, outside the box
        assertEquals((10+3+4)*2,s.apply(10,1,64,1,true,()->false),1e-9); // with the built-in underwater add
        assertTrue(RouteRules.applied().get("twice")>0);
    }
    @Test public void aRouteKeepsAwayFromADropOnlyWhenARuleSaysSo(){
        var t=pitBeside();
        assertTrue("straight past the pit",route(t,10,0).stream().anyMatch(p->p.x>=3&&p.x<=7&&p.z==0));
        set(rule("name","edges","where",rule("drop",rule("depth",4,"within",1)),"factor",10));
        var wide=route(t,10,0);
        assertTrue(wide.toString(),wide.stream().noneMatch(p->p.x>=3&&p.x<=7&&p.z>=0));
        assertTrue(RouteRules.applied().get("edges")>0);
        set(rule("name","edges","enabled",false)); // a field given alone changes only that field
        assertTrue(route(t,10,0).stream().anyMatch(p->p.x>=3&&p.x<=7&&p.z==0));
        assertEquals(10,RouteRules.rules().stream().filter(r->r.name().equals("edges")).findFirst().orElseThrow().factor(),0);
    }
    @Test public void neverIsAWallTheSearchGoesRound(){
        set(rule("name","keep_out","where",rule("box",rule("min",List.of(3,60,-5),"max",List.of(3,70,5))),"never",true));
        assertEquals(ActionCosts.COST_INF,RouteRules.search(null,false,null).apply(5,3,64,0,false,()->false),0);
        var round=route(new Planning.Terrain(),10,0);
        assertTrue(round.toString(),round.stream().noneMatch(p->p.x==3&&Math.abs(p.z)<=5));
    }
    @Test public void besideBlocksAndOpenAboveReadTheTerrain(){
        var t=new Planning.Terrain();
        t.set(5,64,2,Blocks.LAVA,0);for(int y=66;y<70;y++)t.set(0,y,0,Blocks.STONE,0);
        set(rule("name","lava","where",rule("beside_blocks",rule("match",List.of("minecraft:lava"),"within",2)),"add",50));
        set(rule("name","sky","where",rule("open_above",3),"factor",3));
        var s=RouteRules.search(null,false,new baritone.utils.BlockStateInterface(t,(x,z)->true));
        assertEquals((10+50)*3,s.apply(10,4,64,0,false,()->false),1e-9); // lava two across, nothing overhead
        assertEquals(10,s.apply(10,0,64,0,false,()->false),1e-9);        // roofed, no lava near
        assertEquals(10*3,s.apply(10,5,64,-1,false,()->false),1e-9);     // lava three across
    }
    @Test public void aBadSetChangesNothing(){
        var before=RouteRules.rules();
        for(var bad:List.<Map<String,Object>>of(
                rule("name","x","where",rule("nearby",3),"factor",2),                                   // unknown test
                rule("name","x","where",rule("beside_blocks",rule("match",List.of("minecraft:nope"))),"factor",2), // unknown block
                rule("name","x","where",rule("beside_blocks",rule("match",List.of("item=minecraft:stick"))),"factor",2),
                rule("name","x","where",rule()),                                                          // changes nothing
                rule("name","x","factor",2),                                                              // a new rule needs where
                rule("name","Bad Name","where",rule(),"factor",2),
                rule("name","x","where",rule(),"factor",0),
                rule("name","x","where",rule("drop",rule("within",1)),"factor",2))){                     // depth required
            try{call("set","rules",List.of(rule("name","fine","where",rule(),"factor",2),bad));fail(bad.toString());}
            catch(IllegalArgumentException expected){}
            assertEquals(bad.toString(),before,RouteRules.rules());
        }
    }
    @Test public void builtInsAreTurnedOffOrResetNotRemoved(){
        try{call("remove","names",List.of("underwater"));fail();}catch(IllegalArgumentException expected){}
        set(rule("name","underwater","add",9));
        assertEquals(9,RouteRules.rules().get(2).add(),0);
        call("reset","names",List.of("underwater"));
        assertEquals(4,RouteRules.rules().get(2).add(),0);
        set(rule("name","mine","where",rule("dimension",-1),"factor",2));
        try{call("reset","names",List.of("mine"));fail();}catch(IllegalArgumentException expected){}
        call("remove","names",List.of("mine"));
        assertEquals(4,RouteRules.rules().size());
    }
    @Test public void aDimensionTestNeedsAKnownWorld(){
        set(rule("name","nether","where",rule("dimension",0),"factor",2));
        assertEquals("no world, no dimension: the test does not hold",10,RouteRules.search(null,false,null).apply(10,0,64,0,false,()->false),1e-9);
    }
}
