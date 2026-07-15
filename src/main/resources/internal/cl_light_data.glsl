int cl_pack_32(int skyLight4, int red8, int green8, int blue8) {
    int alpha4 = 15;

    return red8 | green8 << 8 | skyLight4 << 16 | blue8 << 20 | alpha4 << 28;
}

int cl_pack_16(int skyLight3, int red4, int blue4, int green3) {
    return (packed_light & 0xFFu) << 0 |
            (packed_light & 0xFFu) << 8 |
            (skyLight4 & 0xFu) << 16 |
            (packed_light & 0xFFu) << 20;
}

// TODO: unpackers
