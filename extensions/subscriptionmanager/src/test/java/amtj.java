public class amtj {
    public final byte[] c;

    amtj(byte[] payload) {
        c = payload;
    }
}

final class amtjChild extends amtj {
    amtjChild(byte[] payload) {
        super(payload);
    }
}

class amvi extends amtj {
    amvi(byte[] payload) {
        super(payload);
    }
}

final class amviChild extends amvi {
    amviChild(byte[] payload) {
        super(payload);
    }
}
