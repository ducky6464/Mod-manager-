package dev.ducky.modmanager;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.glfw.GLFW;
import java.util.List;

public final class KeybindManagerScreen extends Screen {
    private final Screen parent;
    private List<KeyBinding> bindings;
    private int scroll,selected=-1;
    private boolean waiting;
    private static final int ROW=30;
    public KeybindManagerScreen(Screen parent){super(Text.literal("Mod Manager"));this.parent=parent;}
    @Override protected void init(){
        bindings=KeybindScanner.getBindings();
        String remembered=KeybindStore.getSelectedId();
        selected=-1;
        for(int i=0;i<bindings.size();i++) if(bindings.get(i).getId().equals(remembered)){selected=i;break;}
        if(selected>=0) scroll=Math.max(0,selected-Math.max(1,(height-86)/ROW)+1);
        int y=height-27;
        addDrawableChild(ButtonWidget.builder(Text.literal("Restore default"),b->restore()).dimensions(8,y,110,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Rescan"),b->{KeybindScanner.scan();bindings=KeybindScanner.getBindings();scroll=0;}).dimensions(124,y,75,20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"),b->close()).dimensions(width-78,y,70,20).build());
    }
    @Override public void render(DrawContext c,int mx,int my,float delta){
        renderBackground(c,mx,my,delta);
        c.drawCenteredTextWithShadow(textRenderer,title,width/2,8,0xFFFFFF);
        c.drawTextWithShadow(textRenderer,"Found registered keybinds: "+bindings.size(),10,22,0xAAAAAA);
        c.drawTextWithShadow(textRenderer,"Select a row, then press a key. Scroll to see more.",10,34,0xAAAAAA);
        int top=50,visible=Math.max(1,(height-86)/ROW);
        for(int i=scroll;i<Math.min(bindings.size(),scroll+visible);i++){
            KeyBinding k=bindings.get(i);int y=top+(i-scroll)*ROW;
            c.fill(8,y,width-8,y+ROW-2,i==selected?0xB0506080:0x80303030);
            String id=k.getId();
            String modId=id.startsWith("key.")&&id.length()>4?id.substring(4).split("\\.")[0]:"minecraft";
            String modName=FabricLoader.getInstance().getModContainer(modId)
                .map(m->m.getMetadata().getName()).orElse(modId.equals("minecraft")?"Minecraft":modId);
            String label=modName+"  •  "+id;
            c.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(label,Math.max(40,width-28)),14,y+3,0xFFFFFF);
            String keys="Now: "+k.getBoundKeyLocalizedText().getString()+"   |   Default: "+k.getDefaultKey().getLocalizedText().getString();
            c.drawTextWithShadow(textRenderer,textRenderer.trimToWidth(keys,Math.max(40,width-28)),14,y+16,0xFFFF80);
            if(hasConflict(k)) c.drawTextWithShadow(textRenderer,"!",width-18,y+6,0xFF5555);
        }
        if(waiting){c.fill(width/2-115,height/2-18,width/2+115,height/2+18,0xE0202020);c.drawCenteredTextWithShadow(textRenderer,"Press a key (ESC cancels)",width/2,height/2-4,0xFFFFFF);}
        super.render(c,mx,my,delta);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(y>=50&&y<height-32&&x>=8&&x<width-8){int row=(int)((y-50)/ROW)+scroll;if(row>=0&&row<bindings.size()){selected=row;KeybindStore.setSelectedId(bindings.get(row).getId());waiting=true;return true;}}
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(waiting){if(key==GLFW.GLFW_KEY_ESCAPE){waiting=false;return true;}if(selected>=0&&selected<bindings.size()){KeybindStore.set(bindings.get(selected),key);KeybindStore.setSelectedId(bindings.get(selected).getId());waiting=false;}return true;}
        if(key==GLFW.GLFW_KEY_ESCAPE){close();return true;}return super.keyPressed(key,scan,mods);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        int visible=Math.max(1,(height-86)/ROW),max=Math.max(0,bindings.size()-visible);
        scroll=Math.max(0,Math.min(max,scroll-(int)Math.signum(vertical)*3));return true;
    }
    private boolean hasConflict(KeyBinding target){
        int code=KeybindStore.boundCode(target);
        if(code<0)return false;
        for(KeyBinding other:bindings)if(other!=target&&KeybindStore.boundCode(other)==code)return true;
        return false;
    }
    private void restore(){if(selected>=0&&selected<bindings.size())KeybindStore.restore(bindings.get(selected));}
    @Override public void close(){if(waiting){waiting=false;return;}if(client!=null)client.setScreen(parent);}
}
