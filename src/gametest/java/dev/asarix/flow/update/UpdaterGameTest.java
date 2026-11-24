package dev.asarix.flow.update;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.nio.charset.StandardCharsets;
import net.fabricmc.fabric.api.client.gametest.v1.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.input.*;

public final class UpdaterGameTest implements FabricClientGameTest {
    static void require(boolean ok,String error){if(!ok)throw new AssertionError(error);}
    static boolean has(Screen s,String name){return s.children().stream().anyMatch(c->c instanceof AbstractWidget w && w.getMessage().getString().contains(name));}
    static void click(Screen s,String name){
        for(var c:s.children())if(c instanceof AbstractWidget w && w.getMessage().getString().contains(name)){
            s.mouseClicked(new MouseButtonEvent(w.getX()+4,w.getY()+4,new MouseButtonInfo(0,0)),false);return;
        }
        throw new AssertionError("Missing control "+name);
    }
    public void runTest(ClientGameTestContext c){
        var u=new Updates("flow-updater-test-"+UUID.randomUUID());
        try {
            var folder=u.dir.resolve("test-release");Files.createDirectories(folder);
            try(var jar=new JarOutputStream(Files.newOutputStream(folder.resolve("skyblockflow.jar")))){
                jar.putNextEntry(new JarEntry("fabric.mod.json"));
                jar.write("{\"id\":\"skyblockflow\",\"version\":\"0.0.1-beta\",\"depends\":{\"minecraft\":\"~26.2\"}}".getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            String notes="# SkyblockFlow 0.0.1 Beta\n\n- [Feature] Read release notes in game. (Asarix)\n- [Fix] Keep the player profile open during inspection. (Asarix)\n- [Performance] Reuse shop prices while browsing recipes. (Asarix)\n- [Refactor] Share release parsing. (Asarix)";
            Files.writeString(folder.resolve("RELEASE_NOTES.md"),notes);
            Files.writeString(folder.resolve("CHANGE_DETAILS.txt"),"Format: 1\n\n[release-notes]\nType: Feature\nAuthor: Asarix\nSummary: Read release notes in game.\nDetails: Hover a change to read its details.\n");
            Files.writeString(u.dir.resolve("releases-1.json"),"[]");
            c.getInput().resizeWindow(1120,720);
            c.runOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();});
            u.check(true);c.waitFor(mc->u.state==Updates.State.OFFER,300);
            require(u.release.changes().getFirst().detail().startsWith("Hover"),"Details association");
            c.setScreen(TitleScreen::new);
            c.runOnClient(mc->u.tick(mc));
            c.waitFor(mc->mc.gui.screen() instanceof UpdateScreen);
            c.waitTicks(3);c.takeScreenshot("skyflow-update-notes");
            c.runOnClient(mc->{
                var s=mc.gui.screen();require(!has(s,"Share release parsing"),"Developer rows leaked");
                click(s,"Developer mode");require(u.developer,"Developer preference");
            });
            require(new Updates(u.host).developer,"Saved preference");
            c.getInput().resizeWindow(660,500);c.waitTicks(4);
            c.takeScreenshot("skyflow-update-compact");
            c.runOnClient(mc->click(mc.gui.screen(),"Download"));
            c.waitTicks(3);c.takeScreenshot("skyflow-update-consent");
            c.runOnClient(mc->click(mc.gui.screen(),"Back"));
            require(!Files.exists(UpdateInstall.active(u.host,u.dir)),"Consent back installed a JAR");
            synchronized(u){u.prepare();u.download();u.cancel();}
            c.waitFor(mc->u.state==Updates.State.OFFER,300);
            c.waitTicks(2);
            require(!Files.exists(UpdateInstall.active(u.host,u.dir)),"Cancelled transfer installed a JAR");
            c.runOnClient(mc->{click(mc.gui.screen(),"Download");click(mc.gui.screen(),"Download anyway");});
            c.waitFor(mc->u.state==Updates.State.DONE || u.state==Updates.State.ERROR,300);
            require(u.state==Updates.State.DONE,u.message);
            var dest=UpdateInstall.active(u.host,u.dir);
            require(UpdateSwap.hash(dest).equals(u.release.digest()),"Installed different bytes");
            var fresh=new Updates(u.host);fresh.check(true);c.waitFor(mc->fresh.state==Updates.State.EMPTY,300);
            Files.delete(dest);
            fresh.preference(true);
            var optOut=new Updates(u.host);optOut.check(true);c.waitFor(mc->optOut.state==Updates.State.EMPTY,300);
            require(!optOut.beta,"Beta opt-out lost");
            require(Release.compare("0.0.11-beta","0.0.2-beta")>0,"Numeric version sorting");
            var temp=Files.createTempFile(u.dir,"bad",".pending");
            Files.writeString(temp,"bad");
            try{UpdateInstall.verify(temp,u.release);throw new AssertionError("Bad digest accepted");}catch(java.io.IOException expected){}
            Files.delete(temp);
            c.runOnClient(mc->mc.gui.screen().onClose());
        } catch(Exception e){throw new AssertionError(e);}
    }
}
