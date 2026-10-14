// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (c) 2026 ModdedBench contributors
// Derived from Baritone (https://github.com/cabaletta/baritone), LGPL-3.0-or-later.
package baritone.gtnh;

import baritone.Baritone;
import dev.modbench.api.ControlRegistry;
import dev.modbench.api.Engaged;
import dev.modbench.api.InputArbiter;
import dev.modbench.api.Navigation;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;
import java.util.*;
import java.util.function.Predicate;
import static baritone.gtnh.pathing.WorkSpec.*;

/**
 * One fight, at the level mining is one job: the caller names the target and the limits, this does the footwork.
 * The target is whatever entity the caller names (entityId, or a selector). Hostile, for hold's and swarm's targets, the
 * outnumbered guard, what is struck aside and the report, is by default what is after you, by the server's word (you are
 * its target, or it hurt you last) or because this fight hit it, and of the monster rule one steadily coming at you or out
 * of sight: a behaviour, never a kind of mob. A caller's rule replaces it.
 * Out of reach the source FollowProcess paths to the target; in reach the engine rests and this aims, blocks with a
 * sword between swings, lands a swing at one standing still while falling (a critical hit) and one at one coming at you
 * running (it goes twice as far). It ends, as a failure so that the
 * actionFailed guard pauses the world, as soon as the fight is no longer the one that was asked for.
 */
final class FightJob implements Navigation.Job {
    private static final double REACH=3,SIGHT=16;
    private final Minecraft mc=Minecraft.getMinecraft();
    private final Baritone engine;
    private final Object world=mc.theWorld,player=mc.thePlayer;
    private final String scope=ControlRegistry.memory().memory().scope();
    private final Map<baritone.api.Settings.Setting<?>,Object> saved=new LinkedHashMap<>();
    private final Map<String,Object> jobSettings=new LinkedHashMap<>();
    private final InputArbiter.Lease lease;
    private final Integer chosen;
    private final Predicate<Entity> named,hostile,monster,keepAway;
    private final List<Map<String,Object>> hostileRule,keepAwayRule;
    private final boolean hold,swarm,crit,block,byBehaviour;
    private final int duration,interval,maxGrowth;
    private int maxAttackers=2;private boolean attackersSet;
    // Who is after you: the server's word (Engaged, fresh while a fight runs), and whom this fight has hit.
    private final Set<Integer> engaged=new HashSet<>(),struck=new HashSet<>();private boolean informed;
    // Kept away from: the kinds seen to go off beside you in this fight (gone with health left as you took a hit), and the caller's.
    private final Set<String> wentOff=new LinkedHashSet<>();
    private final Map<Integer,Object[]> lastNear=new HashMap<>();private final List<Object[]> vanished=new ArrayList<>();private final float[] felt=new float[4];
    private final Map<Integer,double[]> still=new HashMap<>(); // each close mob: where it was, and for how many ticks it has not moved
    private final Set<Integer> running=new HashSet<>(); // the still ones being run from
    private final Map<Integer,Double> toward=new HashMap<>(); // each monster's own going toward you, summed over about the last second and a half
    private final Map<Integer,Integer> passedUntil=new HashMap<>(); // swarm: the mobs it could not get at, passed over until that tick
    private Entity chased;private int chaseFrom,passedOver;private final List<String> taken=new ArrayList<>(); // each mob it took on, and why it counted as hostile
    private final Set<Integer> present=new HashSet<>(); // the hostiles within 32 blocks when the fight began, seen or not: the fight that was chosen
    private final Map<Integer,double[]> place=new HashMap<>();private final List<double[]> fell=new ArrayList<>();private final Set<Integer> judged=new HashSet<>(); // where each of those is, and where and when one died [x,y,z,tick]
    private final Map<Integer,double[]> gaps=new HashMap<>(); // each hostile's distance, the last four times it was taken (every 10 ticks)
    private final Set<Integer> cameAt=new HashSet<>(),closing=new HashSet<>(); // who has come at you (struck aside), and who is coming now, half a block nearer than half a second ago (met, not walked to)
    private int aside;
    private final double bailHealth,maxHealthLoss,startHealth,ax,ay,az,aimHeight;
    private final boolean leashGiven;
    private double leash;
    private Entity leashed;
    private int ledId=-1,ledX,ledY,ledZ,ledTick;private double paceX,paceY,paceZ; // where(): the server's last word on the target, and its pace
    private final long initialCalculations;
    private final Map<String,Object> failure=new LinkedHashMap<>();
    private String state="fighting",reason="",phase="starting";
    private Entity target;
    // One pass over the loaded entities a tick: the monster rule's, the hostile and the kept-away in sight within 8, nearest first.
    private List<Entity> close=List.of();
    private int ticks,lastAttack=-100,runFrom=-200,swingSlot=-1,lastUseful,attacks,crits,runningHits,kills,clearTicks,outOfReach,heldAgainst;
    // Ranged: nothing here knows a weapon. How it is used is found by trying (hold and release; if nothing flies, click); how
    // its projectile flies comes in as numbers, and each shot's velocity samples go back out for the caller to fit them from.
    private boolean ranged;
    private Integer meleeSlot;
    private float lastHealth=-1;
    private double minRange=6,maxRange=20,speed=3,gravity=.05,drag=.99;
    private int drawTicks=20,reloadTicks=5,shotPhase,phaseTicks,shots,hits,duds,watchUntil=-1,stalled,blind,weaponSlot,launcherSlot=-1,armTick=-1000;
    private boolean clickAfterLoad,shotCounted;
    private String weaponKey="",loaded="";
    private double nearestSeen=Double.MAX_VALUE;
    /** What the last arc tried would strike before the target, if anything did. */
    private Entity inTheWay;
    private final Set<Integer> seenEntities=new HashSet<>();
    private final Set<String> shotKinds=new LinkedHashSet<>();
    private final List<Map<String,Object>> adjustments=new ArrayList<>();
    private Entity tracked;
    private List<double[]> track=new ArrayList<>();
    private final List<List<double[]>> tracks=new ArrayList<>();


    FightJob(Baritone engine,Map<String,Object> params){
        this.engine=engine;
        // Swarm: stand and hit whichever mob of the hostile rule is nearest in reach, at the weapon's full rate, however many come.
        swarm=bool(params,"swarm",false);hold=bool(params,"hold",false);crit=bool(params,"crit",!swarm);block=bool(params,"block",!swarm);
        if(swarm&&(params.containsKey("entityId")||params.containsKey("target")))throw new IllegalArgumentException("swarm fights whatever the hostile rule matches in reach: it takes no entityId or target");
        chosen=params.containsKey("entityId")?integer(params,"entityId",0,Integer.MIN_VALUE,Integer.MAX_VALUE):null;
        named=chosen!=null?e->e.getEntityId()==chosen:params.containsKey("target")?ReferenceFollowJob.selector(child(params,"target")):null;
        // By default hostile is a behaviour: after you. The monster rule (IMob) bounds the pack the fight began with, and
        // stands in for the server's word when none has come for a second.
        byBehaviour=!params.containsKey("hostile");
        hostileRule=byBehaviour?List.of(Map.of("class","net.minecraft.entity.monster.IMob")):list(params.get("hostile")).stream().map(o->object(o)).toList();
        var rules=hostileRule.stream().map(ReferenceFollowJob::selector).toList();monster=e->rules.stream().anyMatch(r->r.test(e));
        hostile=byBehaviour?e->e instanceof EntityLivingBase&&!(e instanceof EntityPlayer)&&provoked(e):monster;
        keepAwayRule=params.containsKey("keepAway")?list(params.get("keepAway")).stream().map(o->object(o)).toList():List.of();
        var keep=keepAwayRule.stream().map(ReferenceFollowJob::selector).toList();keepAway=e->wentOff.contains(type(e))||keep.stream().anyMatch(r->r.test(e));
        if(named==null&&!hold&&!swarm)throw new IllegalArgumentException("fight needs entityId (from obs.entities or the clock's threats) or a target selector {entityId|uuid|type|name|class}, or hold:true to stand and hit whatever the hostile rule matches");
        duration=integer(params,"durationTicks",600,1,6000);interval=integer(params,"intervalTicks",10,10,40);
        leashGiven=params.containsKey("leash");leash=number(params,"leash",16,2,96);bailHealth=number(params,"bailHealth",8,0,40);
        // Going badly, before the bail line: this much health lost in this fight, or this many hostiles come close that were not there when it began.
        maxHealthLoss=number(params,"maxHealthLoss",10,1,100);maxGrowth=integer(params,"maxGrowth",3,0,64);
        // Where on the target the crosshair rests, feet 0 to top 1. A swing lands wherever in reach it is aimed, so it looks low; a shot lands where it is aimed.
        aimHeight=params.containsKey("aimHeight")?number(params,"aimHeight",.6,0,1):-1;
        var me=mc.thePlayer;ax=me.posX;ay=me.boundingBox.minY;az=me.posZ;startHealth=me.getHealth();Arrays.fill(felt,me.getHealth());refresh();close=close();
        for(Object o:mc.theWorld.loadedEntityList)if(o instanceof Entity e&&e!=me&&!dead(e)&&e.getDistanceToEntity(me)<=32&&(monster.test(e)||hostile.test(e)))present.add(e.getEntityId());
        // A pack in sight when the fight was chosen is part of it: closing in is what it was always going to do. Counted once the server has said who is after you.
        if(params.containsKey("maxAttackers")){maxAttackers=integer(params,"maxAttackers",2,1,16);attackersSet=true;}
        if(params.containsKey("weaponSlot")){me.inventory.currentItem=integer(params,"weaponSlot",0,0,8);mc.playerController.updateController();}
        ranged=params.containsKey("ranged");
        if(ranged){
            var held=me.getHeldItem();if(held==null)throw new IllegalArgumentException("ranged fight needs the weapon in hand: pass weaponSlot");
            weaponKey=net.minecraft.item.Item.itemRegistry.getNameForObject(held.getItem())+"|"+held.getDisplayName();launcherSlot=me.inventory.currentItem;
            var asked=child(params,"ranged");
            speed=number(asked,"speed",3,.1,20);gravity=number(asked,"gravity",.05,0,1);drag=number(asked,"drag",.99,.5,1);
            drawTicks=integer(asked,"drawTicks",20,1,200);reloadTicks=integer(asked,"reloadTicks",5,0,400);clickAfterLoad=bool(asked,"clickAfterLoad",false);
            if(asked.containsKey("meleeSlot"))meleeSlot=integer(asked,"meleeSlot",0,0,8);
            minRange=number(asked,"minRange",6,0,32);maxRange=number(asked,"maxRange",20,4,96);
            for(Object o:mc.theWorld.loadedEntityList)seenEntities.add(((Entity)o).getEntityId());
        }
        swingSlot=ranged?meleeSlot==null?-1:meleeSlot:me.inventory.currentItem;
        var settings=Baritone.settings();
        for(var s:List.of(settings.allowBreak,settings.allowPlace,settings.followRadius,settings.followOffsetDistance))saved.put(s,s.value);
        engine.getPathingBehavior().forceCancel();engine.snags.reset();initialCalculations=engine.getPathingBehavior().calculationsStarted();
        lease=ControlRegistry.controls().arbiter().acquire("baritone-fight",this::cancel);
        // For this job only, shown in the receipt and restored when it ends.
        settings.allowBreak.value=bool(params,"allowBreak",false);settings.allowPlace.value=bool(params,"allowPlace",false);settings.followRadius.value=2;settings.followOffsetDistance.value=0d;
        for(var s:saved.keySet())jobSettings.put(s.getName(),s.value);
        engine.overrideProtection=false;engine.named=p->false;engine.positionAllowed=p->true;engine.explicitMiningTargets=()->s->false;
        engine.getInputOverrideHandler().attach(lease);
        if(!hold)engine.getFollowProcess().follow(e->e==target);
    }
    void tick(){
        if(done())return;
        var me=mc.thePlayer;
        if(me==player&&mc.theWorld==world)watch(); // before the end of a fight that a blast ended
        if(WorkAccess.died(player)){finish("failed","player_died");return;}
        if(mc.theWorld!=world||me!=player||!scope.equals(ControlRegistry.memory().memory().scope())){cancel("world_changed");return;}
        if(!lease.isActive()){cancel(WorkAccess.lost(lease,"control_lost"));return;}
        if(mc.currentScreen!=null){cancel("gui_open");return;}
        if(ticks++>=duration){finish("failed","duration_elapsed");return;}
        if(me.getHealth()<=bailHealth){finish("failed","health_at_bail_line");return;}
        if(startHealth-me.getHealth()>=maxHealthLoss){finish("failed","health_lost: "+Math.round((startHealth-me.getHealth())*10)/10.0+" of "+startHealth+" lost in this fight, "+kills+" kills");return;}
        refresh();close=close();
        if(!attackersSet&&(informed||ticks>20)){maxAttackers=Math.max(2,hostiles(16).size());attackersSet=true;}
        // What one of the fight leaves where it dies (a slime's halves come out of its body a second later) is the same fight, not more arriving.
        for(int id:present){Entity e=mc.theWorld.getEntityByID(id);
            if(e!=null&&!dead(e))place.put(id,new double[]{e.posX,e.posY,e.posZ});
            else{double[] at=place.remove(id);if(at!=null)fell.add(new double[]{at[0],at[1],at[2],ticks});}}
        fell.removeIf(f->ticks-f[3]>40);
        for(Object o:mc.theWorld.loadedEntityList)if(o instanceof Entity e&&!present.contains(e.getEntityId())&&judged.add(e.getEntityId())&&monster.test(e)
            &&fell.stream().anyMatch(f->e.getDistance(f[0],f[1],f[2])<=3))present.add(e.getEntityId());
        long arrived=hostiles(8).stream().filter(e->!present.contains(e.getEntityId())).count();
        if(arrived>maxGrowth){finish("failed","swarm_growing: "+arrived+" hostiles within 8 blocks that were not within 32 when the fight began, after "+kills+" kills");return;}
        long atOnce=atOnce(hostiles(4));
        if(!swarm&&attackersSet&&atOnce>maxAttackers){finish("failed","outnumbered: "+atOnce+" hostiles within 4 blocks that could be at you at once (one queued behind a nearer one is not), max_attackers "+maxAttackers);return;}
        if(ticks%10==0)for(Entity e:matching(x->monster.test(x)||hostile.test(x),12)){ // each one's distance over the last second and a half
            double d=e.getDistanceToEntity(me);double[] g=gaps.computeIfAbsent(e.getEntityId(),k->new double[]{d,d,d,d});
            System.arraycopy(g,1,g,0,3);g[3]=d;if(g[0]-d>=1.5)cameAt.add(e.getEntityId());
            if(g[2]-d>=.5)closing.add(e.getEntityId());else closing.remove(e.getEntityId());
        }

        // Standing in what burns (the game's own test: fire or lava in the box, or a block a mod says is burning), a step
        // out of it comes first, to the nearest side with somewhere to stand that does not burn. A drawn weapon stays drawn.
        if(Hazards.hurtsToStand(me)){Float out=Hazards.outOfFire(me);
            if(out!=null){phase="out_of_fire";me.rotationYaw=out;Set<Integer> keys=Hazards.stepKeys(me,out);
                if(drawn())keys.add(mc.gameSettings.keyBindUseItem.getKeyCode());rest(keys);return;}}
        // Thrown by a hit toward a drop: the keys against the throw until the ground, and a crouch while sliding on (Hazards).
        {var back=Hazards.counter(me,false);if(back!=null){if(!phase.equals("held_against_a_hit"))heldAgainst++;phase="held_against_a_hit";rest(back.keys());return;}}
        if(shotPhase==4){lower();return;}
        if(target!=null&&dead(target)&&drawn()){shotPhase=4;phaseTicks=0;lower();return;}
        if(target!=null&&dead(target)){
            // Gone with health left is not a kill: it blew itself up, despawned, or went where the client no longer sees it.
            float left=target instanceof EntityLivingBase l?l.getHealth():0;target=null;
            if(left<=0)kills++;
            if(chosen!=null){if(left<=0)finish("succeeded","target_dead");else finish("failed","target_gone: it left the world with "+round(left)+" health (it exploded, despawned or went out of sight range), not by your hits");return;}
        }
        if(chosen!=null){
            target=mc.theWorld.getEntityByID(chosen);
            if(target==null||dead(target)){target=null;finish("failed",attacks>0?"target_lost":"no_such_entity");return;}
        } else {
            // A selector (or, holding, the hostile rule) keeps its target while it lives, else takes the nearest match in sight.
            if(swarm)target=swarmTarget(target);
            if(target==null&&!swarm){List<Entity> all=named!=null?matching(named,hold?SIGHT:leash):hostiles(SIGHT);target=all.isEmpty()?null:all.get(0);}
            if(target==null){
                // Clear for two seconds, or five while monsters in sight are left alone: long enough for one coming at you to show it.
                phase="clear";rest(Set.of());if(++clearTicks<40||clearTicks<100&&byBehaviour&&!matching(e->monster.test(e)&&!hostile.test(e),SIGHT).isEmpty())return;
                List<Entity> left=swarm?pack():List.of();
                if(left.isEmpty())finish("succeeded","clear"+remaining());
                else finish("failed","cannot_reach_target: "+left.size()+" of the fight passed over, each out of reach for 5 seconds; the nearest "+describe(left.get(0)));
                return;
            }
            clearTicks=0;
        }
        if(target!=chased){chased=target;chaseFrom=ticks;if(target!=null&&taken.size()<16)taken.add(type(target)+" "+target.getEntityId()+": "+why(target));}
        // The leash is on the player: how far this fight may draw you from where it began. A mob knocked back by your own
        // hits, or one that began far off and is coming, is still the fight you chose. Left to the default, the walk to
        // where the mob stood when the fight took it up is not counted against it.
        if(target!=leashed){leashed=target;if(!leashGiven)leash=Math.max(leash,16+Math.ceil(Math.sqrt(Math.pow(target.posX-ax,2)+Math.pow(target.posY-ay,2)+Math.pow(target.posZ-az,2))));}
        if(moved()>leash){finish("failed","beyond_leash: the fight has drawn you "+round(moved())+" blocks from where it began (leash "+round(leash)+"); the target is "+round(target.getDistanceToEntity(me))+" blocks from you");return;}
        if(ticks-lastUseful>200){
            // Ten seconds without a hit. If the engine was walking to the target, how its last search ended is the cause; a
            // search that found a path names none (the target kept its distance), and the evidence is in the receipt either way.
            String cause=phase.equals("pursuing")||phase.equals("closing")?PathFailure.cause(engine,initialCalculations,engine.getPathingBehavior().getGoal(),failure):null;
            String where=hold?ranged?"holding, and no shot at it for 10 seconds ("+phase+")":"holding, and it stayed "+round(reach(target))+" blocks from your eye for 10 seconds":cause==null||cause.equals("path_calculation_failed")?null:cause;
            double up=target.boundingBox.minY-me.boundingBox.minY;String at=" (it is "+round(Math.abs(up))+" blocks "+(up>=0?"above":"below")+" your feet, "+round(Math.hypot(target.posX-me.posX,target.posZ-me.posZ))+" across)";
            finish("failed",(where==null?"cannot_reach_target":"cannot_reach_target: "+where)+at);return;
        }

        // A mob walks faster than a player backs away: a launcher is no use at arm's length. One in sight within 3.5 blocks
        // is met with the melee weapon (one behind a wall is not at you yet), and a second after none is the launcher is back.
        List<Entity> atArm=hostiles(3.5).stream().filter(me::canEntityBeSeen).toList();if(!atArm.isEmpty())armTick=ticks;
        if(ranged&&shotPhase!=1&&shotPhase!=2&&!atArm.isEmpty()){
            if(meleeSlot==null){finish("failed","hostile_in_melee_range: the ranged fight is over, "+shots+" shots; fight on with a melee weapon (or pass ranged.meleeSlot) or leave");return;}
            boolean drawn=drawn();ranged=false;me.inventory.currentItem=meleeSlot;mc.playerController.updateController();
            if(chosen==null||reach(target)>REACH)target=atArm.get(0);
            rest(drawn?Set.of(mc.gameSettings.keyBindUseItem.getKeyCode()):Set.of());if(drawn)return;
        }
        if(!ranged&&launcherSlot>=0&&ticks-armTick>=20){ranged=true;shotPhase=0;phaseTicks=0;me.inventory.currentItem=launcherSlot;mc.playerController.updateController();}
        if(ranged){rangedTick(target);return;}
        Entity body=nearest(target);double far=boxReach(body);
        boolean inReach=far<=REACH&&touchable(body);
        // The engine puts blocks in hand to build its way to the target: in reach, the weapon is back in hand a tick before a swing.
        if(inReach&&swingSlot>=0&&me.inventory.currentItem!=swingSlot){me.inventory.currentItem=swingSlot;mc.playerController.updateController();rest(Set.of());return;}
        Set<Integer> keys=new LinkedHashSet<>();var game=mc.gameSettings;int since=ticks-lastAttack;
        // One kept away from that stands still close by is getting ready: a sprint away from it onto somewhere to stand, until
        // it is 8 blocks off or comes at you again. With nowhere to go, a swing when one is ready, where it is.
        Entity lit=hold?null:lit();
        if(lit!=null){
            phase="keeping_away";lastUseful=ticks;Entity part=nearest(lit);boolean hit=boxReach(part)<=REACH&&touchable(part)&&since>=interval;
            Float away=escape(lit);
            if(away==null){aim(part);if(hit)strike(part,false);}
            else{me.rotationYaw=away;me.rotationPitch=0;keys.add(game.keyBindForward.getKeyCode());keys.add(game.keyBindSprint.getKeyCode());}
            rest(keys);return;
        }
        // One kept away from is fought at arm's length: struck as it comes into reach, and backed away from while the next
        // swing gathers, so it is not let within 3.5 blocks with no swing ready.
        Entity kept=hold?null:close.stream().filter(e->keepAway.test(e)&&touchable(e)&&e.getDistanceToEntity(me)<3.5).findFirst().orElse(null);
        if(kept!=null){
            Entity part=nearest(kept);Float away=since>=interval&&boxReach(part)<=REACH?null:escape(kept);
            if(away==null&&since>=interval&&boxReach(part)<=REACH){phase="arms_length";lastUseful=ticks;aim(part);if(!runningIn(keys)){strike(part,false);rest(keys);}return;}
            if(away!=null){phase="arms_length";me.rotationYaw=away;me.rotationPitch=0;keys.add(game.keyBindForward.getKeyCode());keys.add(game.keyBindSprint.getKeyCode());rest(keys);return;}
        }
        // A swarm passes over a mob it cannot get at (three seconds beside it out of reach, or five without a hit since it
        // took it up) and takes the next; with only those left it says so rather than clear.
        if(swarm&&!inReach&&(outOfReach>=60||ticks-Math.max(chaseFrom,lastUseful)>=100)){
            passedUntil.put(target.getEntityId(),ticks+200);passedOver++;target=null;outOfReach=0;lastUseful=ticks;rest(Set.of());return;
        }
        if(!inReach&&!hold&&!swarm){
            // Another that has come at you, in reach while the target is not: struck where it stands, not walked past.
            Entity other=hostiles(REACH+2).stream().filter(e->e!=target&&cameAt.contains(e.getEntityId())&&reach(e)<=REACH).findFirst().orElse(null);
            if(other!=null){body=nearest(other);inReach=true;}
        }
        boolean struckAside=inReach&&body!=nearest(target);
        if(!inReach&&!hold){
            // Beside or under it and still out of reach: it is above or below where walking can take a swing. Wait for it
            // (jumping when a jump would do), and say so after three seconds rather than walk in circles for ten.
            if(Math.hypot(target.posX-me.posX,target.posZ-me.posZ)<=2.5&&me.canEntityBeSeen(body)){
                if(++outOfReach>=60&&!swarm){finish("failed","cannot_reach_target: it has stayed "+round(far)+" blocks from your eye, above or below where you can stand, for 3 seconds (reach "+round(REACH)+")");return;}
                phase="out_of_reach";aim(body);if(far<=REACH+1.2&&me.onGround)keys.add(game.keyBindJump.getKeyCode());rest(keys);return;
            }
            outOfReach=0;
            if(closing.contains(target.getEntityId())&&comingAt(target)&&me.canEntityBeSeen(body)){
                // It is coming, by its own going this tick (the gap shrinks as you walk too, and one that has stopped short is
                // walked to): met here, with the first swing at the edge of reach, rather than walked into with whatever comes with it.
                phase="awaiting";aim(body);
                var held=me.getHeldItem();if(block&&held!=null&&held.getItemUseAction()==net.minecraft.item.EnumAction.block){keys.add(game.keyBindUseItem.getKeyCode());if(!me.isUsingItem())mc.playerController.sendUseItem(me,mc.theWorld,held);}
                rest(keys);return;
            }
            phase="pursuing";if(!building())eyes(body,false);engine.tickStart();return;
        }
        outOfReach=0;
        aim(body);
        phase=struckAside?"striking_aside":inReach?"striking":"holding";
        boolean falling=!me.onGround&&me.fallDistance>0&&!me.isInWater()&&!me.isOnLadder();
        boolean timed=crit&&!comingAt(target)&&!keepAway.test(target); // one coming at you is hit the moment it is in reach: the knockback is worth more than the crit
        if(!timed&&inReach&&since>=interval&&runningIn(keys))return;
        var held=me.getHeldItem();
        if(block&&held!=null&&held.getItemUseAction()==net.minecraft.item.EnumAction.block){
            keys.add(game.keyBindUseItem.getKeyCode());
            if(!me.isUsingItem())mc.playerController.sendUseItem(me,mc.theWorld,held); // a held key keeps the block up; it does not raise it
        }
        if(timed&&inReach&&me.onGround&&since>=interval-6)keys.add(game.keyBindJump.getKeyCode());
        if(inReach&&since>=interval&&(!timed||falling||since>=interval+10)){strike(body,falling);if(struckAside)aside++;}
        rest(keys);
    }
    /**
     * A swing made running carries the run's knockback: the game adds a level for a sprinting hit, and the mob goes about
     * twice as far (some 6 blocks rather than 3). So first a tick or two of forward and sprint, the sword lowered; not when
     * holding ground, already running, too hungry to run, or with no floor a block ahead. True while that step is being taken.
     */
    private boolean runningIn(Set<Integer> keys){
        var me=mc.thePlayer;var game=mc.gameSettings;
        if(hold||me.isSprinting()||me.getFoodStats().getFoodLevel()<=6||runFrom>=lastAttack&&ticks-runFrom>2||!standable(me.rotationYaw,1))return false;
        if(runFrom<lastAttack)runFrom=ticks;
        keys.add(game.keyBindForward.getKeyCode());keys.add(game.keyBindSprint.getKeyCode());rest(keys);return true;
    }
    private void strike(Entity body,boolean falling){
        if(mc.thePlayer.isSprinting())runningHits++;
        mc.playerController.attackEntity(mc.thePlayer,body);mc.thePlayer.swingItem();attacks++;struck.add(body.getEntityId());passedUntil.remove(body.getEntityId());if(target!=null)struck.add(target.getEntityId());if(falling)crits++;lastAttack=lastUseful=ticks;
    }
    /** The engine does not tick here, so its keys and any block it was breaking are let go and ours are published. */
    private void rest(Set<Integer> keys){
        var input=engine.getInputOverrideHandler();input.clearAllKeys();input.getBlockBreakHelper().stopBreakingBlock();
        lease.setKeys(keys);lease.look(mc.thePlayer.rotationYaw,mc.thePlayer.rotationPitch);
    }
    private void aim(Entity e){
        var me=mc.thePlayer;double[] at=where(e);
        me.rotationYaw=(float)(Math.toDegrees(Math.atan2(at[2]-me.posZ,at[0]-me.posX))-90);me.rotationPitch=pitch(e,false);
    }
    private float pitch(Entity e,boolean shot){
        var me=mc.thePlayer;double[] at=shot?new double[]{e.posX,e.boundingBox.minY,e.posZ}:where(e);double dx=at[0]-me.posX,dz=at[2]-me.posZ;
        return (float)-Math.toDegrees(Math.atan2(at[1]-e.boundingBox.minY+aimY(e,shot)-eyeY(),Math.sqrt(dx*dx+dz*dz)));
    }
    /**
     * Where a target at arm's length is now, feet: {x,y,z}. The client draws a mob sliding to where the server last put
     * it, and the server says where every third tick, so a mob coming fast stands a block nearer than it is drawn; a look
     * laid on the drawn one falls short of where it was meant. This is the server's last word carried on at the pace of
     * its last two. An entity the server never moves by itself (a part of a larger one) is where it is drawn.
     */
    private double[] where(Entity e){
        if(e.serverPosX==0&&e.serverPosY==0&&e.serverPosZ==0)return new double[]{e.posX,e.boundingBox.minY,e.posZ};
        if(e.getEntityId()!=ledId){ledId=e.getEntityId();ledX=e.serverPosX;ledY=e.serverPosY;ledZ=e.serverPosZ;ledTick=ticks;paceX=paceY=paceZ=0;}
        else if(e.serverPosX!=ledX||e.serverPosY!=ledY||e.serverPosZ!=ledZ){
            double per=32.0*Math.max(1,ticks-ledTick);
            paceX=(e.serverPosX-ledX)/per;paceY=(e.serverPosY-ledY)/per;paceZ=(e.serverPosZ-ledZ)/per;
            if(paceX*paceX+paceY*paceY+paceZ*paceZ>1)paceX=paceY=paceZ=0; // over a block a tick is no walk: a teleport, a blast
            ledX=e.serverPosX;ledY=e.serverPosY;ledZ=e.serverPosZ;ledTick=ticks;
        }
        int age=ticks-ledTick;if(age>4)paceX=paceY=paceZ=0; // no word for longer than the server leaves between two: it stands still
        double lead=Math.min(age,3)+1; // and the tick this look takes to reach the server
        return new double[]{e.serverPosX/32.0+paceX*lead,e.serverPosY/32.0+paceY*lead,e.serverPosZ/32.0+paceZ*lead};
    }
    /** Walking to a target, the eyes rest at the height the fight aims at (for a shot, while it is in view). The engine
     *  turns the head along its path and leaves a pitch it has no use for where it finds it, except to build (see building). */
    private void eyes(Entity e,boolean shot){if(!shot||mc.thePlayer.canEntityBeSeen(e))mc.thePlayer.rotationPitch=pitch(e,shot);}
    /** The engine's move under way has a block still to place or break: where it looks is where it clicks, so the eyes
     *  are left to it. Set at the mob, a placed block never lands, and a stair up to a mob on a ledge is never built. */
    private boolean building(){
        var walk=engine.getPathingBehavior().getCurrent();
        if(walk==null||walk.getPosition()>=walk.getPath().movements().size())return false;
        var m=(baritone.pathing.movement.Movement)walk.getPath().movements().get(walk.getPosition());var bsi=new baritone.utils.BlockStateInterface(engine.getPlayerContext());
        return !m.toPlace(bsi).isEmpty()||!m.toBreak(bsi).isEmpty();
    }
    /** A weapon held drawn: the use key is down, and letting it go looses the shot. */
    private boolean drawn(){return ranged&&shotPhase==0&&phaseTicks>0;}
    /** Lets a drawn weapon down unfired, as a player does by scrolling off it: one tick on another slot ends the use on
     *  both sides with nothing loosed. The key stays down for that tick; let go in the same tick, the shot would fly. */
    private void lower(){
        var me=mc.thePlayer;
        if(phaseTicks++==0){weaponSlot=me.inventory.currentItem;me.inventory.currentItem=(weaponSlot+1)%9;mc.playerController.updateController();rest(Set.of(mc.gameSettings.keyBindUseItem.getKeyCode()));return;}
        me.inventory.currentItem=weaponSlot;mc.playerController.updateController();shotPhase=0;phaseTicks=0;blind=0;phase="lowered";rest(Set.of());
    }
    /** The local player's eye: its client copy's own eye height is an offset from its stance, not from its feet. */
    private double eyeY(){return mc.thePlayer.getPosition(1).yCoord;}
    private double aimY(Entity e,boolean shot){var b=e.boundingBox;return b.minY+(b.maxY-b.minY)*(aimHeight>=0?aimHeight:shot?.6:.2);}
    private double moved(){var me=mc.thePlayer;double x=me.posX-ax,y=me.boundingBox.minY-ay,z=me.posZ-az;return Math.sqrt(x*x+y*y+z*z);}
    private static double round(double v){return Math.round(v*10)/10.0;}
    /** What a swing lands on: the entity, or for one made of parts (the game takes hits on those alone) the part nearest the eye. */
    private Entity nearest(Entity e){
        Entity[] parts=e.getParts();if(parts==null||parts.length==0)return e;
        Entity best=parts[0];for(Entity p:parts)if(boxReach(p)<boxReach(best))best=p;
        return best;
    }
    private double reach(Entity e){return boxReach(nearest(e));}
    /** Eye to the nearest point of the box, which is what the server measures a hit by. */
    private double boxReach(Entity e){
        var me=mc.thePlayer;var b=e.boundingBox;double ex=me.posX,ey=eyeY(),ez=me.posZ;
        double dx=Math.max(Math.max(b.minX-ex,0),ex-b.maxX),dy=Math.max(Math.max(b.minY-ey,0),ey-b.maxY),dz=Math.max(Math.max(b.minZ-ez,0),ez-b.maxZ);
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    /**
     * A swarm's target, each tick: the one it is hitting while that is in reach (one finished before the next), else the
     * nearest in reach (one passed over too), else the one it is going to while no other is two blocks nearer, else the
     * nearest of the pack it has not passed over.
     */
    private Entity swarmTarget(Entity current){
        var me=mc.thePlayer;
        // One of a kind kept away from, within 6 blocks, first: met at arm's length before it is beside you.
        Entity kept=pack().stream().filter(e->!passed(e)&&keepAway.test(e)&&e.getDistanceToEntity(me)<=6).findFirst().orElse(null);if(kept!=null)return kept;
        if(current!=null&&!dead(current)&&!passed(current)&&hostile.test(current)&&reach(current)<=REACH)return current;
        Entity inReach=hostiles(REACH+2).stream().filter(e->reach(e)<=REACH).findFirst().orElse(null); // one passed over that has come into reach is reachable after all
        if(inReach!=null)return inReach;
        List<Entity> all=pack().stream().filter(e->!passed(e)).toList();if(all.isEmpty())return null;
        if(current!=null&&!dead(current)&&all.contains(current)&&current.getDistanceToEntity(me)<all.get(0).getDistanceToEntity(me)+2)return current;
        return all.get(0);
    }
    /** A swarm's mobs, nearest first: the hostiles in sight within 16 blocks, and of those within 32 when it began, the ones in sight or within 16. */
    private List<Entity> pack(){
        var me=mc.thePlayer;List<Entity> out=new ArrayList<>(hostiles(SIGHT));
        for(Object o:mc.theWorld.loadedEntityList)if(o instanceof Entity e&&present.contains(e.getEntityId())&&!out.contains(e)&&!dead(e)&&hostile.test(e)&&e.getDistanceToEntity(me)<=32&&(e.getDistanceToEntity(me)<=SIGHT||me.canEntityBeSeen(e)))out.add(e);
        out.sort(Comparator.comparingDouble(e->e.getDistanceSqToEntity(me)));return out;
    }
    /** Of the hostiles within 32 blocks when the fight began, how many are still alive and loaded, and the nearest's
     *  distance; and how many of the monster rule in sight are not after you, which hostile by behaviour leaves alone. */
    private String remaining(){
        var me=mc.thePlayer;List<Double> far=new ArrayList<>();
        for(Object o:mc.theWorld.loadedEntityList)if(o instanceof Entity e&&present.contains(e.getEntityId())&&!dead(e)&&hostile.test(e))far.add((double)e.getDistanceToEntity(me));
        String left=far.isEmpty()?"":": "+far.size()+" of the hostiles within 32 blocks when it began still alive, none in sight within 32 nor within 16, the nearest "+round(Collections.min(far))+" blocks off";
        int calm=byBehaviour?matching(e->monster.test(e)&&!hostile.test(e),SIGHT).size():0;
        return calm==0?left:left+(left.isEmpty()?": ":"; ")+calm+" of the monster rule in sight within 16 not after you, left alone";
    }
    /** The server's word on who is after you, if one has come in the last two seconds. */
    private void refresh(){
        int[] now=Engaged.latest(2000);informed=now!=null;if(now!=null){engaged.clear();for(int id:now)engaged.add(id);}
        var me=mc.thePlayer;Map<Integer,Double> next=new HashMap<>();
        for(Object o:mc.theWorld.loadedEntityList)if(o instanceof Entity e&&e!=me&&e.getDistanceToEntity(me)<=16&&monster.test(e)){
            double dx=me.posX-e.posX,dz=me.posZ-e.posZ,d=Math.hypot(dx,dz),step=d>0?((e.posX-e.prevPosX)*dx+(e.posZ-e.prevPosZ)*dz)/d:0;
            next.put(e.getEntityId(),toward.getOrDefault(e.getEntityId(),0.0)*29/30+Math.max(-1,Math.min(1,step)));
        }
        toward.clear();toward.putAll(next);
    }
    /**
     * After you: the server says so (you are its target, or it hurt you last), or this fight hit it. Of the monster rule, one
     * steadily coming at you is (two blocks of its own going in about a second and a half: your walking does not count), and
     * one out of your sight is taken to be until it shows otherwise; one in sight and not after you is minding its own business.
     * With no word from the server for a second, the monster rule stands in.
     */
    private String why(Entity e){
        int id=e.getEntityId();if(!swarm&&named!=null&&named.test(e))return "named";if(!byBehaviour)return "the hostile rule";
        if(struck.contains(id))return "hit by you";if(informed&&engaged.contains(id))return "after you, by the server's word";
        if(!monster.test(e))return "named";if(toward.getOrDefault(id,0.0)>=2)return "coming at you";
        return informed?"out of sight":"the monster rule, no word from the server";
    }
    private boolean provoked(Entity e){
        int id=e.getEntityId();if(struck.contains(id)||informed&&engaged.contains(id))return true;
        return monster.test(e)&&(toward.getOrDefault(id,0.0)>=2||(informed?!mc.thePlayer.canEntityBeSeen(e):ticks>20));
    }
    /**
     * Each tick, of the mobs close by: how long each has stood still, and whether one went off beside you, gone from the
     * world with health left within 5 blocks as you lost health. Its kind is kept away from after that (one that killed you too).
     */
    private void watch(){
        var me=mc.thePlayer;
        for(var n:lastNear.entrySet()){Entity e=mc.theWorld.getEntityByID(n.getKey());if((e==null||e.isDead)&&(float)n.getValue()[1]>0)vanished.add(new Object[]{n.getValue()[0],ticks});}
        vanished.removeIf(v->ticks-(int)v[1]>3);
        felt[ticks%4]=me.getHealth();float most=0;for(float f:felt)most=Math.max(most,f);
        if(most-me.getHealth()>0){for(Object[] v:vanished)wentOff.add((String)v[0]);vanished.clear();}
        lastNear.clear();Map<Integer,double[]> now=new HashMap<>();
        for(Entity e:close){
            if(e instanceof EntityLivingBase l&&e.getDistanceToEntity(me)<=5)lastNear.put(e.getEntityId(),new Object[]{type(e),l.getHealth()});
            double[] s=still.get(e.getEntityId());boolean same=s!=null&&Math.abs(s[0]-e.posX)<.02&&Math.abs(s[1]-e.posY)<.02&&Math.abs(s[2]-e.posZ)<.02;
            now.put(e.getEntityId(),new double[]{e.posX,e.posY,e.posZ,same?s[3]+1:0});
        }
        still.clear();still.putAll(now);
    }
    /** The nearest one kept away from that has stood still for 4 ticks within 4 blocks; once run from, until it is 8 blocks off or comes at you again. */
    private Entity lit(){
        var me=mc.thePlayer;
        for(Entity e:close){double[] s=still.get(e.getEntityId());float d=e.getDistanceToEntity(me);
            if(keepAway.test(e)&&(s!=null&&s[3]>=4&&d<=4||running.contains(e.getEntityId())&&d<=8&&!comingAt(e))){running.add(e.getEntityId());return e;}}
        running.clear();return null;
    }
    /** It moved nearer you this tick by more than 0.03 blocks of its own going (a mob walks about 0.1 a tick). */
    private boolean comingAt(Entity e){
        var me=mc.thePlayer;double dx=me.posX-e.posX,dz=me.posZ-e.posZ,d=Math.hypot(dx,dz);
        return d>0&&((e.posX-e.prevPosX)*dx+(e.posZ-e.prevPosZ)*dz)/d>.03;
    }
    /**
     * A swing would land: in sight eye to eye (the server takes a hit from 6 blocks then), or else within 3 blocks with
     * nothing between your eye and where the crosshair rests on it (the server takes an unseen hit from 3), as legs seen
     * under a low roof are.
     */
    private boolean touchable(Entity e){
        var me=mc.thePlayer;if(me.canEntityBeSeen(e))return true;
        double dx=e.posX-me.posX,dy=e.posY-me.boundingBox.minY,dz=e.posZ-me.posZ;if(dx*dx+dy*dy+dz*dz>=9)return false;
        return mc.theWorld.rayTraceBlocks(Vec3.createVectorHelper(me.posX,eyeY(),me.posZ),Vec3.createVectorHelper(e.posX,aimY(e,false),e.posZ))==null;
    }
    private boolean passed(Entity e){Integer until=passedUntil.get(e.getEntityId());return until!=null&&ticks<until;}
    private String describe(Entity e){var me=mc.thePlayer;return type(e)+" (entity "+e.getEntityId()+") "+round(e.getDistanceToEntity(me))+" blocks off, "+round(e.boundingBox.minY-me.boundingBox.minY)+" above your feet";}
    private static String type(Entity e){return String.valueOf(net.minecraft.entity.EntityList.getEntityString(e));}
    private static boolean dead(Entity e){return e.isDead||e instanceof EntityLivingBase l&&l.getHealth()<=0;}
    private List<Entity> close(){return matching(e->e instanceof EntityLivingBase&&!(e instanceof EntityPlayer)&&(monster.test(e)||hostile.test(e)||keepAway.test(e)),8);}
    /** Entities of the hostile rule the player can see within `radius`, nearest first: out of this tick's pass up to 8 blocks. */
    private List<Entity> hostiles(double radius){
        if(radius>8)return matching(hostile,radius);
        var me=mc.thePlayer;return close.stream().filter(e->hostile.test(e)&&e.getDistanceToEntity(me)<=radius).toList();
    }
    private List<Entity> matching(Predicate<Entity> rule,double radius){
        var me=mc.thePlayer;List<Entity> out=new ArrayList<>();
        // Distance first: it is arithmetic, where the rule may walk a class's ancestry and sight traces a ray.
        for(Object value:mc.theWorld.loadedEntityList)
            if(value instanceof Entity e&&e!=me&&!dead(e)&&e.getDistanceToEntity(me)<=radius&&rule.test(e)&&touchable(e))out.add(e);
        out.sort(Comparator.comparingDouble(e->e.getDistanceSqToEntity(me)));return out;
    }
    private void rangedTick(Entity t){
        var me=mc.thePlayer;var game=mc.gameSettings;int use=game.keyBindUseItem.getKeyCode();
        watchShot();
        float health=t instanceof EntityLivingBase l?l.getHealth():-1;
        if(lastHealth>=0&&health>=0&&health<lastHealth&&shots>0)hits++;
        lastHealth=health;
        // In sight is seen, and the arc the shot would fly clear of blocks: a mob seen over the lip of cover is not one to shoot into it.
        double distance=me.getDistanceToEntity(t);boolean seen=me.canEntityBeSeen(t)||visibleAim(t),sight=seen&&aimBallistic(t);String unseen=seen?"no_clear_shot":"no_line_of_sight";
        // A weapon already drawn is not dropped, which would loose it unaimed: in sight it is shot from here, whatever the
        // range; out of sight it is held a second for the target to show again, then let down unfired.
        if(drawn()&&!sight){
            phase=unseen;
            if(++blind<20){rest(Set.of(use));return;}
            shotPhase=4;phaseTicks=0;lower();return;
        }
        blind=0;
        if((!sight||distance>maxRange)&&shotPhase==0&&!drawn()&&!hold){
            // In sight and no nearer after three seconds of walking (it is up a pillar, across a gap, in the air): shoot from here.
            if(distance<nearestSeen-.5){nearestSeen=distance;stalled=0;}
            if(sight&&distance<=96&&++stalled>=60){double to=Math.ceil(distance)+2;adjust("maxRange",maxRange,to,"closing got no nearer for three seconds: shooting from here");maxRange=to;stalled=0;}
            else{phase="closing";phaseTicks=0;eyes(t,true);engine.tickStart();return;}
        }
        Set<Integer> keys=new LinkedHashSet<>();
        if(distance<minRange&&clearBehind())keys.add(game.keyBindBack.getKeyCode());
        phaseTicks++;
        switch(shotPhase){
            case 0->{
                if(!sight){phaseTicks=0;phase=unseen;break;}
                phase="drawing";keys.add(use);
                if(phaseTicks==1){loaded=wear(me.getHeldItem());shotCounted=false;mc.playerController.sendUseItem(me,mc.theWorld,me.getHeldItem());}
                if(phaseTicks>=drawTicks){shotPhase=1;phaseTicks=0;watchUntil=ticks+12;} // the use key is left out from the next tick on: that is the release
            }
            case 1->{
                phase="released";
                if(phaseTicks>=(clickAfterLoad?3:10)){
                    if(wore())countShot();
                    else if(clickAfterLoad){shotPhase=2;phaseTicks=0;}
                    else{clickAfterLoad=true;shotPhase=2;phaseTicks=0;adjust("clickAfterLoad",false,true,"nothing flew on release; perhaps it only loaded");}
                }
            }
            case 2->{
                phase="firing";
                if(phaseTicks==1){watchUntil=ticks+12;mc.playerController.sendUseItem(me,mc.theWorld,me.getHeldItem());}
                if(phaseTicks<=2)keys.add(use);else if(phaseTicks>=12){if(wore())countShot();else dud();}
            }
            default->{phase="reloading";if(phaseTicks>=reloadTicks){shotPhase=0;phaseTicks=0;}}
        }
        rest(keys);
    }
    /** A change this job made to the numbers it was given, for the receipt. */
    private void adjust(String key,Object from,Object to,String why){if(adjustments.size()<8)adjustments.add(Map.of(key,List.of(from,to),"why",why));}
    /** Neither releasing nor clicking sent anything: wind longer next time; three in a row is no ammunition, or a weapon this cannot work. */
    private void dud(){
        duds++;int before=drawTicks;drawTicks=Math.min(100,drawTicks+10);shotPhase=0;phaseTicks=0;
        adjust("drawTicks",before,drawTicks,"nothing flew: wind longer next time, at most 100 ticks");
        if(duds>=3)finish("failed","no_projectile_fired: out of ammunition, or this weapon is not used by holding, releasing or clicking");
    }
    /** The weapon in hand as a shot changes it: a launcher wears, a throwable is one fewer. */
    private static String wear(net.minecraft.item.ItemStack s){return s==null?"":net.minecraft.item.Item.itemRegistry.getNameForObject(s.getItem())+"|"+s.getItemDamage()+"|"+s.stackSize;}
    /** Nothing was seen to fly, yet the weapon wore or a throwable left the hand: it went. */
    private boolean wore(){return !shotCounted&&!wear(mc.thePlayer.getHeldItem()).equals(loaded);}
    /** A shot left the hand, after a release or after a click: that is how this weapon is used, and the cycle goes on to reloading. */
    private void countShot(){
        boolean clicked=shotPhase==2;
        if(clickAfterLoad!=clicked)adjust("clickAfterLoad",clickAfterLoad,clicked,"the shot flew after "+(clicked?"a click":"a release"));
        shots++;duds=0;shotCounted=true;clickAfterLoad=clicked;shotPhase=3;phaseTicks=0;lastUseful=ticks;
    }
    /** Whatever new entity that is not a living one appears near the player in the half second after a release or click,
     *  flying away from it, is this shot, of whatever class a mod gives it (the receipt names it); follow it to learn how
     *  the weapon flies. A fast shot's first client tick is already blocks from the hand, so near is 12 blocks. */
    private void watchShot(){
        var me=mc.thePlayer;boolean window=ticks<=watchUntil;
        for(Object o:mc.theWorld.loadedEntityList){
            Entity e=(Entity)o;
            if(e instanceof EntityLivingBase||!seenEntities.add(e.getEntityId()))continue;
            // Away: its next step is farther from the eye. Not the side of the eye it is on: a shot appears just below and to one side, so one aimed up starts "behind" its own flight.
            double x=e.posX-me.posX,y=e.posY-eyeY(),z=e.posZ-me.posZ,nx=x+e.motionX,ny=y+e.motionY,nz=z+e.motionZ;boolean away=nx*nx+ny*ny+nz*nz>x*x+y*y+z*z;
            if(window&&tracked==null&&e.getDistanceToEntity(me)<12&&away&&e.motionX*e.motionX+e.motionY*e.motionY+e.motionZ*e.motionZ>.09){
                tracked=e;shotKinds.add(e.getClass().getName());if(!shotCounted)countShot();
            }
        }
        if(tracked==null)return;
        // The projectile's own velocity, which its client copy steps with the weapon's real constants; positions jitter with every server correction.
        double[] last=track.isEmpty()?null:track.get(track.size()-1);
        double h=Math.sqrt(tracked.motionX*tracked.motionX+tracked.motionZ*tracked.motionZ);
        boolean stopped=tracked.isDead||h<.05||last!=null&&h>last[0]*1.02; // landed, or knocked about by something
        if(!stopped)track.add(new double[]{h,tracked.motionY});
        if(stopped||track.size()>=8){if(track.size()>=3)tracks.add(track);track=new ArrayList<>();tracked=null;}
    }
    /** Height reached, and ticks taken, when a shot launched at `angle` has covered `far` blocks of ground. */
    private double[] fly(double angle,double far){
        double x=0,y=0,vx=speed*Math.cos(angle),vy=speed*Math.sin(angle);int t=0;
        while(x<far&&t<200&&vx>.01){x+=vx;y+=vy;vx*=drag;vy=vy*drag-gravity;t++;}
        return new double[]{x<far?-1e9:y,t};
    }
    /** Aims at where `t` will be when the shot arrives; true if the arc it flies there is clear of blocks a shot cannot pass
     *  and of anything else it would strike first.
     *  Blocked at the usual height on the mob (cover to its waist, a lip over you), a higher point on it, then a lower, is tried. */
    private boolean aimBallistic(Entity t){
        double[] heights=aimHeight>=0?new double[]{aimHeight}:new double[]{.6,.9,.3};inTheWay=null;
        for(double h:heights)if(aimAt(t,h))return true;
        aimAt(t,heights[0]);return false;
    }
    /** Cover may hide the eyes while leaving the aiming point in view. */
    private boolean visibleAim(Entity t){
        var me=mc.thePlayer;var b=t.boundingBox;
        double[] heights=aimHeight>=0?new double[]{aimHeight}:new double[]{.6,.9,.3};
        // A fresh eye each time: the trace moves its start to every block face it crosses.
        for(double h:heights)if(mc.theWorld.rayTraceBlocks(Vec3.createVectorHelper(me.posX,eyeY(),me.posZ),Vec3.createVectorHelper(t.posX,b.minY+(b.maxY-b.minY)*h,t.posZ))==null)return true;
        return false;
    }
    private boolean aimAt(Entity t,double height){
        var me=mc.thePlayer;var b=t.boundingBox;double ex=me.posX,ey=eyeY()-.1,ez=me.posZ,flight=0,angle=0,dx=0,dz=0;
        for(int pass=0;pass<2;pass++){ // second pass leads the target by where it will be when the shot arrives
            dx=t.posX+(t.posX-t.prevPosX)*flight-ex;dz=t.posZ+(t.posZ-t.prevPosZ)*flight-ez;
            double far=Math.sqrt(dx*dx+dz*dz),up=b.minY+(b.maxY-b.minY)*height+(t.posY-t.prevPosY)*flight-ey,lo=-Math.PI/2+.01,hi=Math.PI/4;
            for(int i=0;i<18;i++){angle=(lo+hi)/2;double[] r=fly(angle,far);flight=r[1];if(r[0]<up)lo=angle;else hi=angle;}
        }
        me.rotationYaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90);me.rotationPitch=(float)-Math.toDegrees(angle);
        // The same arc stepped through the world, up to half a block short of the target (it is hit before what is behind it).
        double far=Math.sqrt(dx*dx+dz*dz),ux=dx/far,uz=dz/far,x=0,y=0,vx=speed*Math.cos(angle),vy=speed*Math.sin(angle);
        for(int i=0;i<200&&x<far-.5&&vx>.01;i++){
            double nx=Math.min(x+vx,far-.5),ny=y+vy*(nx-x)/vx;
            if((inTheWay=struck(t,Vec3.createVectorHelper(ex+ux*x,ey+y,ez+uz*x),Vec3.createVectorHelper(ex+ux*nx,ey+ny,ez+uz*nx)))!=null)return false;
            if(mc.theWorld.func_147447_a(Vec3.createVectorHelper(ex+ux*x,ey+y,ez+uz*x),Vec3.createVectorHelper(ex+ux*nx,ey+ny,ez+uz*nx),false,true,false)!=null)return false;
            x=nx;y=ny;vx*=drag;vy=vy*drag-gravity;
        }
        return true;
    }
    /** What a shot flying from a to b strikes other than `t` or a part of it: anything alive it collides with, its box grown by 0.3 as the game grows it
     *  for a projectile. Not what is not alive: a projectile coming at you lies on the line of fire, and a shot into it costs only the arrow. */
    private Entity struck(Entity t,Vec3 a,Vec3 b){
        var parts=t.getParts()==null?List.<Entity>of():Arrays.asList(t.getParts());
        var box=net.minecraft.util.AxisAlignedBB.getBoundingBox(Math.min(a.xCoord,b.xCoord),Math.min(a.yCoord,b.yCoord),Math.min(a.zCoord,b.zCoord),Math.max(a.xCoord,b.xCoord),Math.max(a.yCoord,b.yCoord),Math.max(a.zCoord,b.zCoord)).expand(1,1,1);
        for(Object o:mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer,box)){var e=(Entity)o;
            if(e!=t&&!parts.contains(e)&&e instanceof net.minecraft.entity.EntityLivingBase&&e.canBeCollidedWith()&&e.boundingBox.expand(.3,.3,.3).calculateIntercept(a,b)!=null)return e;}
        return null;
    }
    /** One step back is somewhere to stand: floor under it, room in it, no fluid. */
    private boolean clearBehind(){return standable(mc.thePlayer.rotationYaw+180,1.3);}
    /** The heading to run on from a mob: straight away, else up to 90 degrees off it, with two blocks to stand on ahead; null if none. */
    private Float escape(Entity from){
        var me=mc.thePlayer;float away=(float)(Math.toDegrees(Math.atan2(me.posZ-from.posZ,me.posX-from.posX))-90);
        for(float off:new float[]{0,35,-35,70,-70,90,-90})if(standable(away+off,1.3)&&standable(away+off,2.3))return away+off;
        return null;
    }
    /** How many of these could be at you at once: one queued behind a nearer one (within 30 degrees of it, seen from you)
     *  waits its turn, as in a doorway or a corridor; a pack that spreads round you counts in full. */
    private long atOnce(List<Entity> near){ var me=mc.thePlayer;
        return near.stream().filter(h->near.stream().noneMatch(g->g!=h&&g.getDistanceToEntity(me)<h.getDistanceToEntity(me)
            &&Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_double(Math.toDegrees(Math.atan2(g.posZ-me.posZ,g.posX-me.posX)-Math.atan2(h.posZ-me.posZ,h.posX-me.posX))))<30)).count(); }
    /** The cell that far ahead on that heading has a floor, room for feet and head, and no fluid. */
    private boolean standable(float yaw,double far){
        int[] c=Hazards.cell(mc.thePlayer,yaw,far);return Hazards.standable(mc.theWorld,c[0],(int)Math.floor(mc.thePlayer.boundingBox.minY+.01),c[1]);
    }
    private void finish(String state,String reason){
        if(done())return;this.state=state;this.reason=reason;
        // A launcher still drawn is let down, not loosed: letting the key go would shoot it wherever the eyes are. Another
        // item in hand ends the draw on the server, the client's own draw is dropped so no release is sent, and the slot
        // comes back two of the player's ticks later (letDown).
        var me=mc.thePlayer;
        if(ranged&&me!=null&&me.isUsingItem()){downSlot=me.inventory.currentItem;downAt=me.ticksExisted+2;me.inventory.currentItem=(downSlot+1)%9;mc.playerController.updateController();me.clearItemInUse();}
        engine.getPathingBehavior().forceCancel();engine.getInputOverrideHandler().release();
        saved.forEach(ReferenceSettings::copy);if(lease!=null)lease.close();
    }
    @Override public void cancel(String reason){finish("cancelled",reason);}
    /** The slot of a launcher a fight let down as it ended, and the player's tick from which to put it back. */
    private static int downSlot=-1,downAt;
    /** Each client tick: puts back the slot a drawn launcher was let down from (finish), unless something has changed it since. */
    static void letDown(){
        var me=Minecraft.getMinecraft().thePlayer;if(downSlot<0||me==null||me.ticksExisted<downAt)return;
        if(me.inventory.currentItem==(downSlot+1)%9){me.inventory.currentItem=downSlot;Minecraft.getMinecraft().playerController.updateController();}
        downSlot=-1;
    }
    @Override public boolean done(){return !state.equals("fighting");}
    @Override public boolean succeeded(){return state.equals("succeeded");}
    @Override public Map<String,Object> status(){
        var out=new LinkedHashMap<String,Object>();var me=mc.thePlayer;
        out.put("action","fight");out.put("swarm",swarm);out.put("state",state);out.put("reason",reason);out.put("phase",phase);out.put("ticks",ticks);
        out.put("attacks",attacks);out.put("criticalHits",crits);out.put("runningHits",runningHits);out.put("kills",kills);if(aside>0)out.put("struckAside",aside);if(heldAgainst>0)out.put("heldAgainstThrows",heldAgainst);if(passedOver>0)out.put("passedOver",passedOver);out.put("taken",taken);
        if(shots>0||ranged||!adjustments.isEmpty()){out.put("shots",shots);out.put("hitsObserved",hits);out.put("weapon",weaponKey);
            out.put("ballistics",Map.of("speed",speed,"gravity",gravity,"drag",drag,"drawTicks",drawTicks,"reloadTicks",reloadTicks,"clickAfterLoad",clickAfterLoad));
            out.put("adjustments",adjustments);out.put("shotEntities",List.copyOf(shotKinds));
            out.put("tracks",tracks.stream().map(t->t.stream().map(s->List.of(s[0],s[1])).toList()).toList());} // per shot, per tick: [horizontal speed, vertical speed]
        if(me==player){
            out.put("health",me.getHealth());var inHand=downSlot>=0?me.inventory.getStackInSlot(downSlot):me.getHeldItem();out.put("held",inHand==null?null:inHand.getDisplayName());out.put("movedFromStart",round(moved()));out.put("leash",round(leash));
            Map<String,Object> t=null;
            if(target!=null){t=new LinkedHashMap<>();t.put("entityId",target.getEntityId());t.put("type",String.valueOf(net.minecraft.entity.EntityList.getEntityString(target)));t.put("class",target.getClass().getName());
                t.put("health",target instanceof EntityLivingBase l?l.getHealth():null);t.put("hostile",hostile.test(target));t.put("distance",Math.round(target.getDistanceToEntity(me)*10)/10.0);
                // Out of sight or with no clear shot: the first block on the straight line from your eye to where it would be aimed.
                if(phase.equals("no_line_of_sight")||phase.equals("no_clear_shot")){var b=target.boundingBox;double h=aimHeight>=0?aimHeight:.6;
                    var hit=mc.theWorld.rayTraceBlocks(Vec3.createVectorHelper(me.posX,eyeY(),me.posZ),Vec3.createVectorHelper(target.posX,b.minY+(b.maxY-b.minY)*h,target.posZ));
                    if(hit!=null)t.put("lineBlockedAt",Map.of("pos",List.of(hit.blockX,hit.blockY,hit.blockZ),"block",String.valueOf(net.minecraft.block.Block.blockRegistry.getNameForObject(mc.theWorld.getBlock(hit.blockX,hit.blockY,hit.blockZ))),"aimHeight",h));
                    if(inTheWay!=null&&phase.equals("no_clear_shot"))t.put("shotBlockedBy",Map.of("entityId",inTheWay.getEntityId(),"type",String.valueOf(net.minecraft.entity.EntityList.getEntityString(inTheWay)),"distance",Math.round(inTheWay.getDistanceToEntity(me)*10)/10.0));}}
            out.put("target",t);
            // Whoever is left is the next decision: the same rows obs.entities gives, so nothing here is new knowledge.
            out.put("hostilesInSight",hostiles(16).stream().map(e->Map.of("entityId",e.getEntityId(),"type",String.valueOf(net.minecraft.entity.EntityList.getEntityString(e)),"distance",Math.round(e.getDistanceToEntity(me)*10)/10.0)).toList());
        }
        if(!failure.isEmpty())out.put("failure",failure);
        // While it walks to the target: the engine's move under way, the clue to a pursuit that gets no nearer.
        var walk=done()?null:engine.getPathingBehavior().getCurrent();
        if(walk!=null&&walk.getPosition()<walk.getPath().movements().size()){var m=walk.getPath().movements().get(walk.getPosition());
            out.put("movement",m.getClass().getSimpleName().replace("Movement","")+" "+List.of(m.getSrc().x,m.getSrc().y,m.getSrc().z)+" -> "+List.of(m.getDest().x,m.getDest().y,m.getDest().z));}
        out.put("hostileRule",hostileRule);out.put("hostileBy",!byBehaviour?"rule":informed?"behaviour: after you by the server's word, or hit by you":"the monster rule: no word from the server");
        if(!keepAwayRule.isEmpty())out.put("keepAwayRule",keepAwayRule);if(!wentOff.isEmpty())out.put("wentOff",List.copyOf(wentOff));
       out.put("jobSettings",jobSettings);
        out.put("controlOwned",!done()&&lease.isActive());out.put("scope",scope);return out;
    }
}
