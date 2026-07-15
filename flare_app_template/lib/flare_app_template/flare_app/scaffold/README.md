flare currently two region one is bottom bar and another content area . and more scaffold more memmory each scaffold assign seprate channel .


if you need more saffold areas .. you needed to update the client side code to ...
in andorid 


contentMount = new Mount("content", findViewById(R.id.fl_content));
persistentMounts.put("bottom_bar", new Mount("bottom_bar", findViewById(R.id.fl_bottom_bar)));


to this 

contentMount = new Mount("content", findViewById(R.id.fl_content));
persistentMounts.put("bottom_bar", new Mount("bottom_bar", findViewById(R.id.fl_bottom_bar)));
persistentMounts.put("top_bar",    new Mount("top_bar",    findViewById(R.id.fl_top_bar)));
persistentMounts.put("drawer",     new Mount("drawer",     findViewById(R.id.fl_drawer)));
persistentMounts.put("end_drawer", new Mount("end_drawer", findViewById(R.id.fl_end_drawer)));
persistentMounts.put("overlay",     new Mount("overlay",     findViewById(R.id.fl_overlay)));


======================
private final List<String> SCAFFOLD_REGIONS =
        Arrays.asList("bottom_bar"); 


to this 

private final List<String> SCAFFOLD_REGIONS =
        Arrays.asList("bottom_bar", "top_bar", "drawer", "end_drawer");


==========================

activity_flare_client.xml to this 

<?xml version="1.0" encoding="utf-8"?>

<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:attr/colorBackground">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">


        <FrameLayout
            android:id="@+id/fl_top_bar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />


        <FrameLayout
            android:id="@+id/fl_content"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1" />


        <FrameLayout
            android:id="@+id/fl_bottom_bar"
            android:layout_width="match_parent"
            android:layout_height="wrap_content" />

    </LinearLayout>


    <com.example.flare_android_client.TransitionOverlayView
        android:id="@+id/transition_overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />


    <FrameLayout
        android:id="@+id/fl_drawer"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />


    <FrameLayout
        android:id="@+id/fl_end_drawer"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />


    <FrameLayout
        android:id="@+id/fl_overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

</FrameLayout>






............................. for web ......................




<script>
  window.__flare__ = {
    wsUrl: "/socket",
    token: null,
    entryScreen: "home",
    persistentScreens: [
      { screen: "bottom_bar", region: "bottom_bar" }
    ],
    scaffoldRegions: ["bottom_bar"]
  };
</script>



this to this ..


<script>
    window.__flare__ = {
      wsUrl: "/socket",
      token: null,
      entryScreen: "home",
      persistentScreens: [
        { screen: "bottom_bar", region: "bottom_bar" },
        { screen: "overlay",     region: "overlay" },
        { screen: "top_bar",    region: "top_bar" },
        { screen: "drawer",     region: "drawer" },
        { screen: "end_drawer", region: "end_drawer" }
      ],
      scaffoldRegions: ["bottom_bar", "top_bar", "drawer", "end_drawer"]
    };
  </script>









and in server side the flare_router.ex inside flare_app to this 

defmodule FlareAppTemplate.FlareApp.FlareRouter do
  use Flare.Router

  screen "home",           FlareAppTemplate.FlareApp.Screens.Home,                        scaffold: [:bottom_bar, :top_bar, :drawer, :end_drawer]
  screen "place_setup",    FlareAppTemplate.FlareApp.Screens.PlaceSetup,                  scaffold: [:top_bar]
  screen "add_place",      FlareAppTemplate.FlareApp.Screens.AddPlace,                    scaffold: [:top_bar, :bottom_bar]
  screen "simple_counter", FlareAppTemplate.FlareApp.Screens.WaitTemplates.SimpleCounter, scaffold: [:top_bar]

  screen "bottom_bar", FlareAppTemplate.FlareApp.Scaffold.BottomBar, region: :bottom_bar
  screen "overlay",     FlareAppTemplate.FlareApp.Scaffold.Overlay,    region: :overlay
  screen "top_bar",    FlareAppTemplate.FlareApp.Scaffold.TopBar,    region: :top_bar
  screen "drawer",     FlareAppTemplate.FlareApp.Scaffold.Drawer,    region: :drawer
  screen "end_drawer",  FlareAppTemplate.FlareApp.Scaffold.EndDrawer, region: :end_drawer
end
