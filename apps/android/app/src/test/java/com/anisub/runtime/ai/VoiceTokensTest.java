package com.anisub.runtime.ai;
import org.junit.Test;
import static org.junit.Assert.*;
public class VoiceTokensTest {
    private final String valid="_ 0\n^ 1\n$ 2\n  3\n3 133\nɕ 55\n";
    @Test public void spaceAndDigitRemainDifferentTokens() throws Exception {VoiceTokens.validate(valid);}
    @Test public void nativeFatalFormatsAreRejectedBeforeJni() throws Exception {
        for(String bad:new String[]{valid.replace("\n","\r\n"),valid+"3 134\n",valid+"x 0\n",valid+"garbage\n",valid+"\n",valid+"\t 99\n"}){
            try{VoiceTokens.validate(bad);fail();}catch(java.io.IOException expected){}
        }
    }
}
