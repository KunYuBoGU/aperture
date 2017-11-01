package mchorse.aperture.camera;

import mchorse.aperture.Aperture;
import mchorse.aperture.ClientProxy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.GameType;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.PlayerTickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Profile runner
 *
 * This class is responsible for running camera profiles (i.e. applying current's
 * fixture camera transformations on player).
 */
@SideOnly(Side.CLIENT)
public class CameraRunner
{
    private Minecraft mc = Minecraft.getMinecraft();
    private float fov = 70.0F;
    private GameType gameMode = GameType.NOT_SET;
    private boolean firstTick = false;

    protected boolean isRunning = false;
    protected long ticks;
    protected long duration;

    protected CameraProfile profile;
    protected Position position = new Position(0, 0, 0, 0, 0);

    /* Used by camera renderer */
    public float yaw = 0.0F;
    public float pitch = 0.0F;

    /* Profile access methods */

    public CameraProfile getProfile()
    {
        return this.profile;
    }

    public void setProfile(CameraProfile profile)
    {
        this.profile = profile;
    }

    public boolean isRunning()
    {
        return this.isRunning;
    }

    public long getTicks()
    {
        return this.ticks;
    }

    public void setTicks(long ticks)
    {
        this.ticks = ticks;
    }

    /* Playback methods (start/stop) */

    public void toggle(CameraProfile profile, long ticks)
    {
        if (this.isRunning)
        {
            this.stop();
        }
        else
        {
            this.start(profile, ticks);
        }
    }

    /**
     * Start the profile runner from the first tick 
     */
    public void start(CameraProfile profile)
    {
        this.start(profile, 0);
    }

    /**
     * Start the profile runner. This method also responsible for setting
     * important values before starting the run (like setting duration, and
     * reseting ticks).
     */
    public void start(CameraProfile profile, long start)
    {
        this.profile = profile;

        if (this.profile == null || this.profile.getCount() == 0)
        {
            return;
        }

        if (!this.isRunning)
        {
            this.fov = this.mc.gameSettings.fovSetting;
            this.gameMode = this.getGameMode(this.mc.thePlayer);
            this.position.set(this.mc.thePlayer);

            if (Aperture.proxy.config.camera_spectator && this.gameMode != GameType.SPECTATOR)
            {
                this.mc.thePlayer.sendChatMessage("/gamemode 3");
            }

            MinecraftForge.EVENT_BUS.register(this);
        }

        this.isRunning = true;
        this.firstTick = true;
        this.duration = this.profile.getDuration();
        this.ticks = start;
    }

    /**
     * Get game mode of the given player 
     */
    public GameType getGameMode(EntityPlayer player)
    {
        NetworkPlayerInfo networkplayerinfo = Minecraft.getMinecraft().getConnection().getPlayerInfo(player.getGameProfile().getId());

        return networkplayerinfo != null ? networkplayerinfo.getGameType() : GameType.CREATIVE;
    }

    /**
     * Stop playback of camera profile 
     */
    public void stop()
    {
        if (this.isRunning)
        {
            if (Aperture.proxy.config.camera_spectator && this.gameMode != GameType.SPECTATOR)
            {
                this.mc.thePlayer.sendChatMessage("/gamemode " + this.gameMode.getID());
            }

            if (Aperture.proxy.config.camera_minema)
            {
                ClientCommandHandler.instance.executeCommand(this.mc.thePlayer, "/minema disable");
            }

            this.mc.gameSettings.fovSetting = this.fov;
            this.gameMode = null;

            MinecraftForge.EVENT_BUS.unregister(this);
        }

        this.isRunning = false;
        this.profile = null;

        ClientProxy.control.resetRoll();
    }

    /**
     * The method that does the most exciting thing! This method is responsible
     * for applying interpolated fixture on position and apply the output from
     * fixture onto player.
     */
    @SubscribeEvent
    public void onRenderTick(RenderTickEvent event)
    {
        if (this.profile == null || this.mc.thePlayer == null)
        {
            this.stop();

            return;
        }

        if (event.phase == Phase.START)
        {
            return;
        }

        if (this.firstTick)
        {
            /* Currently Minema supports client side /minema command which
             * record video */
            if (Aperture.proxy.config.camera_minema)
            {
                ClientCommandHandler.instance.executeCommand(this.mc.thePlayer, "/minema enable");
            }

            this.firstTick = false;
        }

        long progress = Math.min(this.ticks, this.duration);

        if (progress >= this.duration)
        {
            this.stop();
        }
        else
        {
            if (Aperture.proxy.config.camera_debug_ticks)
            {
                Aperture.LOGGER.info("Camera render frame: " + event.renderTickTime + " " + this.ticks);
            }

            float prevX = this.position.point.x;
            float prevY = this.position.point.y;
            float prevZ = this.position.point.z;

            this.profile.applyProfile(progress, event.renderTickTime, this.position);

            EntityPlayer player = this.mc.thePlayer;
            Point point = this.position.point;
            Angle angle = this.position.angle;

            /* Setting up the camera */
            this.mc.gameSettings.fovSetting = angle.fov;
            ClientProxy.control.roll = angle.roll;

            /* Fighting with Optifine disappearing entities bug */
            double y = point.y + Math.sin(progress) * 0.000000001 + 0.000000001;

            /* Velocity simulation (useful for recording the player) */
            if (Aperture.proxy.config.camera_simulate_velocity)
            {
                if (this.ticks == 0)
                {
                    this.setPlayerPosition(player, point.x, y, point.z, angle);
                }
                else
                {
                    this.setPlayerPosition(player, player.posX, player.posY, player.posZ, angle);
                }

                player.motionX = this.position.point.x - prevX;
                player.motionY = this.position.point.y - prevY;
                player.motionZ = this.position.point.z - prevZ;
            }
            else
            {
                this.setPlayerPosition(player, point.x, y, point.z, angle);

                player.motionX = player.motionY = player.motionZ = 0;
            }

            if (!this.mc.isSingleplayer())
            {
                float dx = point.x - prevX;
                float dy = point.y - prevY;
                float dz = point.z - prevZ;

                if (dx * dx + dy * dy + dz * dz >= 10 * 10)
                {
                    this.mc.thePlayer.sendChatMessage("/tp " + point.x + " " + point.y + " " + point.z + " " + angle.yaw + " " + angle.pitch);
                }
            }

            this.yaw = angle.yaw;
            this.pitch = angle.pitch;

            if (player.isSneaking())
            {
                player.setSneaking(false);
            }
        }
    }

    /**
     * Set player position
     * 
     * This method is responsible for setting player's position and rotation. 
     * Why are these two methods invoked? Good question. 
     * 
     * {@link EntityPlayer#setLocationAndAngles(double, double, double, float, float)} 
     * updates some client side values such as lastTick* and prevPos* values, 
     * which makes the transition between two distant cameras, seamless, meanwhile 
     * {@link EntityPlayer#setPositionAndRotation(double, double, double, float, float)} 
     * is responsible for fixing/clamping player's rotation.
     * 
     * By using only one of these methods wouldn't guarantee supreme quality of 
     * the camera animation.
     */
    public void setPlayerPosition(EntityPlayer player, double x, double y, double z, Angle angle)
    {
        player.setLocationAndAngles(x, y, z, angle.yaw, angle.pitch);
        player.setPositionAndRotation(x, y, z, angle.yaw, angle.pitch);
    }

    /**
     * This is going to count ticks (used for camera synchronization)
     */
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent event)
    {
        if (event.side == Side.CLIENT && event.player == this.mc.thePlayer && event.phase == Phase.START)
        {
            if (Aperture.proxy.config.camera_debug_ticks)
            {
                Aperture.LOGGER.info("Camera frame: " + this.ticks);
            }

            this.ticks++;
        }
    }
}