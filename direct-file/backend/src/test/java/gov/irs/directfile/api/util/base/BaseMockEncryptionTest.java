package gov.irs.directfile.api.util.base;

import com.amazonaws.encryptionsdk.CryptoMaterialsManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;

import gov.irs.directfile.api.util.SecurityTestConfiguration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({SecurityTestConfiguration.class})
public class BaseMockEncryptionTest {
    @MockitoBean
    public CryptoMaterialsManager mockCryptoMaterialsManager;
}
