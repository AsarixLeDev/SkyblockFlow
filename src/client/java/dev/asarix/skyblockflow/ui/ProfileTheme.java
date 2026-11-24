package dev.asarix.skyblockflow.ui;

/** Surfaces, type and interactive states for the public player profile. */
final class ProfileTheme {
  static final ProfileTheme LIGHT=new ProfileTheme(false), DARK=new ProfileTheme(true);
  final boolean dark;
  final int paper,card,ink,muted,rule,rail,headerTop,headerBottom,focus,subtle,accent,
      sky,horizon,mapText,mapMuted,track,progress,chip,goldChip,goldText,widget,hover,shadow,
      row,selected,portraitTop,portraitBottom;
  private ProfileTheme(boolean dark){
    this.dark=dark;
    paper=dark?0xff16212c:0xfff4f1e9;card=dark?0xff202e3b:0xfffffdf8;
    ink=dark?0xffedf3f3:0xff202c34;muted=dark?0xffa8bac5:0xff586872;
    rule=dark?0xff334653:0xffd7d4ca;rail=dark?0xff101b26:0xff172a34;
    headerTop=dark?0xff27394b:0xff223943;headerBottom=dark?0xff172735:0xff172a35;
    focus=dark?0xff30484a:0xffd4e5dc;subtle=dark?0xff293b49:0xffe2e2d9;
    accent=dark?0xff9ddfc6:0xff286153;
    sky=dark?0xff18293c:0xffd6e7e5;horizon=dark?0xff273a44:0xffeef1e6;
    mapText=dark?0xffe6eeec:0xff223d42;mapMuted=dark?0xffb1c6c8:0xff49666a;
    track=dark?0xff40525b:0xffaebfba;progress=dark?0xff8acdad:0xff508c73;
    chip=dark?0xff334952:0xffe0e9df;goldChip=dark?0xff4d4231:0xfff1dfb5;
    goldText=dark?0xfff7d699:0xff714f21;widget=dark?0xff1c2d39:0xfffafaf0;
    hover=dark?0x99435962:0x85edf6e7;shadow=dark?0x660a1621:0x28244340;
    row=dark?0xff1b2936:0xffeae8df;selected=dark?0xff304f49:0xffc6e2cf;
    portraitTop=dark?0xff47616a:0xff547c76;portraitBottom=dark?0xff1d3244:0xff1b3743;
  }
}
