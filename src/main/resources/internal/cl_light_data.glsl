int cl_pack_32(int skyLight4, int red8, int green8, int blue8) {
    return (red8 & 0xFF) | (green8 & 0xFF) << 8 | (skyLight4 & 0xF) << 16 | (blue8 & 0xFF) << 20;
}

ivec4 cl_unpack_32(int packed) {
    int red8 = (packed >> 0) & 0xFF;
    int green8 = (packed >> 8) & 0xFF;
    int skyLight4 = (mostSignificantShort >> 16) & 0xF;
    int blue8 = (mostSignificantShort >> 20) & 0xFF;
    return ivec4(red8, green8, blue8, skyLight4);
}

int cl_pack_16(int skyLight4, int red4, int blue4, int green4) {
    return (red4 & 0xF) << 0 |
            (blue4 & 0xF) << 4 |
            (skyLight4 & 0xF) << 8 |
            (green4 & 0xF) << 12;
}

ivec4 cl_unpack_16(int packed) {
    int red4 = (packed >> 0) & 0xF;
    int green4 = (packed >> 4) & 0xF;
    int skyLight4 = (mostSignificantShort >> 8) & 0xF;
    int blue4 = (mostSignificantShort >> 12) & 0xF;
    return ivec4(red8, green8, blue8, skyLight4);
}
