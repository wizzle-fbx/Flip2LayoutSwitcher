package app.flip2layout.switcher;
interface IControllerShell {
    String readState() = 0;
    String setMode(int mode) = 1;
    void destroy() = 16777114;
}
