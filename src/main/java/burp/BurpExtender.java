package burp;

import burp.error.SigCredentialProviderException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import com.alibaba.cloudapi.client.AppConfiguration;
import com.alibaba.cloudapi.client.constant.ContentType;
import com.alibaba.cloudapi.client.constant.HttpSchema;
import com.alibaba.cloudapi.client.HttpUtil;
import com.alibaba.cloudapi.client.constant.HttpMethod;


import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.io.UnsupportedEncodingException;


public class BurpExtender implements IBurpExtender, IHttpListener, ITab, IExtensionStateListener, IMessageEditorTabFactory, IContextMenuFactory
{
    // make sure to update version in build.gradle as well
    private static final String EXTENSION_VERSION = "0.1.0";

    private static final String BURP_SETTINGS_KEY = "JsonSettings";
    private static final String SETTING_VERSION = "ExtensionVersion";
    private static final String SETTING_LOG_LEVEL = "LogLevel";
    private static final String SETTING_CONFIG_VERSION = "SettingsVersion";

    public static final String EXTENSION_NAME = "Aliyun API Gateway Signer"; // Name in extender menu
    public static final String DISPLAY_NAME = "Aliyun API Signer"; // name for tabs, menu, and other UI components

    private static final String NO_DEFAULT_PROFILE = "        "; // ensure combobox is visible. SigProfile.profileNamePattern doesn't allow this name

    // Regex for extracting usable signature fields and for just identifying a request as SigV4 (loose)
    private static final Pattern authorizationHeaderRegex = Pattern.compile("^x-ca-signature:[ ]{1,20}AWS4-HMAC-SHA256[ ]{1,20}Credential=(?<accessKeyId>[\\w]{16,128})/(?<date>[0-9]{8})/(?<region>[a-z0-9-]{5,64})/(?<service>[a-z0-9-]{1,64})/aws4_request,[ ]{1,20}SignedHeaders=(?<headers>[\\w;-]+),[ ]{1,20}Signature=(?<signature>[a-z0-9]{64})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern authorizationHeaderLooseRegex = Pattern.compile("^Authorization:[ ]{1,20}AWS4-HMAC-SHA256[ ]{1,20}Credential=(?<accessKeyId>[\\w-]{0,128})/(?<date>[\\w-]{0,8})/(?<region>[\\w-]{0,64})/(?<service>[\\w-]{0,64})/aws4_request,[ ]{1,20}SignedHeaders=(?<headers>[\\w;-]+),[ ]{1,20}Signature=(?<signature>[\\w-]{0,64})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern authorizationHeaderLooseNoCaptureRegex = Pattern.compile("^Authorization:[ ]{1,20}AWS4-HMAC-SHA256[ ]{1,20}Credential=[\\w-]{0,128}/[\\w-]{0,8}/[\\w-]{0,64}/[\\w-]{0,64}/aws4_request,[ ]{1,20}SignedHeaders=[\\w;-]+,[ ]{1,20}Signature=[\\w-]{0,64}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern xAmzCredentialRegex = Pattern.compile("^(?<accessKeyId>[\\w]{16,128})/(?<date>[0-9]{8})/(?<region>[a-z0-9-]{5,64})/(?<service>[a-z0-9-]{1,64})/aws4_request$", Pattern.CASE_INSENSITIVE);

    // define headers for internal use
    public static final String HEADER_PREFIX = "X-BurpSigV4-";
    public static final String PROFILE_HEADER_NAME = HEADER_PREFIX + "Profile"; // used to specify a named profile to sign the request with
    public static final String SKIP_SIGNING_HEADER = HEADER_PREFIX + "Skip: DO NOT SIGN"; // do not sign any requests that contain this header

    protected IExtensionHelpers helpers;
    protected IBurpExtenderCallbacks callbacks;
    private HashMap<String, SigProfile> profileKeyIdMap; // map accessKeyId to profile
    private HashMap<String, SigProfile> profileNameMap; // map name to profile
    protected LogWriter logger = LogWriter.getLogger();

    private JLabel statusLabel;
    private JCheckBox signingEnabledCheckBox;
    private JComboBox<String> defaultProfileComboBox;
    private JComboBox<Object> logLevelComboBox;
    private JCheckBox persistProfilesCheckBox;
    private JCheckBox inScopeOnlyCheckBox;
    private JTextField additionalSignedHeadersField;
    private AdvancedSettingsDialog advancedSettingsDialog;

    private JTable profileTable;
    private JTable customHeadersTable;
    private JCheckBox customHeadersOverwriteCheckbox;
    private JScrollPane outerScrollPane;

    // mimic burp colors
    protected static final Color textOrange = new Color(255, 102, 51);
    protected static final Color darkOrange = new Color(226, 73, 33);

    private static BurpExtender burpInstance;

    public static BurpExtender getBurp()
    {
        return burpInstance;
    }

    public BurpExtender() {}

    private void buildUiTab()
    {
        final Font sectionFont = new JLabel().getFont().deriveFont(Font.BOLD, 15);

        //
        // global settings, checkboxes
        //
        JPanel globalSettingsPanel = new JPanel();
        globalSettingsPanel.setLayout(new GridBagLayout());
        JLabel settingsLabel = new JLabel("Settings");
        settingsLabel.setForeground(BurpExtender.textOrange);
        settingsLabel.setFont(sectionFont);
        JPanel checkBoxPanel = new JPanel();
        signingEnabledCheckBox = new JCheckBox("Signing Enabled");
        signingEnabledCheckBox.setToolTipText("Enable SigV4 signing");
        inScopeOnlyCheckBox = new JCheckBox("In-scope Only");
        inScopeOnlyCheckBox.setToolTipText("Sign in-scope requests only");
        persistProfilesCheckBox = new JCheckBox("Persist Profiles");
        persistProfilesCheckBox.setToolTipText("Save profiles, including keys, in Burp settings store");
        checkBoxPanel.add(signingEnabledCheckBox);
        checkBoxPanel.add(inScopeOnlyCheckBox);
        checkBoxPanel.add(persistProfilesCheckBox);
        JPanel otherSettingsPanel = new JPanel();
        defaultProfileComboBox = new JComboBox<>();
        logLevelComboBox = new JComboBox<>();
        otherSettingsPanel.add(new JLabel("Log Level"));
        otherSettingsPanel.add(logLevelComboBox);
        otherSettingsPanel.add(new JLabel("Default Profile"));
        otherSettingsPanel.add(defaultProfileComboBox);

        JButton advancedSettingsButton = new JButton("Advanced");
        advancedSettingsButton.addActionListener(actionEvent -> {
            advancedSettingsDialog.setVisible(true);
        });
        checkBoxPanel.add(new JSeparator(SwingConstants.VERTICAL));
        checkBoxPanel.add(advancedSettingsButton);
        advancedSettingsDialog = AdvancedSettingsDialog.get();
        advancedSettingsDialog.applyExtensionSettings(new ExtensionSettings()); // load with defaults for now

        GridBagConstraints c00 = new GridBagConstraints(); c00.anchor = GridBagConstraints.FIRST_LINE_START; c00.gridy = 0; c00.gridwidth = 2;
        GridBagConstraints c01 = new GridBagConstraints(); c01.anchor = GridBagConstraints.FIRST_LINE_START; c01.gridy = 1; c01.gridwidth = 2; c01.insets = new Insets(10, 0, 10, 0);
        GridBagConstraints c02 = new GridBagConstraints(); c02.anchor = GridBagConstraints.FIRST_LINE_START; c02.gridy = 2;
        GridBagConstraints c03 = new GridBagConstraints(); c03.anchor = GridBagConstraints.FIRST_LINE_START; c03.gridy = 3;

        globalSettingsPanel.add(settingsLabel, c00);
        globalSettingsPanel.add(new JLabel("<html>Change plugin behavior. Set <i>Default Profile</i> to force signing of all requests with the specified profile credentials."), c01);
        globalSettingsPanel.add(checkBoxPanel, c02);
        globalSettingsPanel.add(otherSettingsPanel, c03);

        //
        // status label
        //
        JPanel statusPanel = new JPanel();
        statusLabel = new JLabel();
        statusPanel.add(statusLabel);

        //
        // profiles table
        //
        JPanel profilePanel = new JPanel(new GridBagLayout());
        JLabel profileLabel = new JLabel("Aliyun API Gateway Credentials");
        profileLabel.setForeground(BurpExtender.textOrange);
        profileLabel.setFont(sectionFont);

        JButton addProfileButton = new JButton("Add");
        JButton editProfileButton = new JButton("Edit");
        JButton removeProfileButton = new JButton("Remove");
        //JButton testProfileButton = new JButton("Test");
        JButton importProfileButton = new JButton("Import");
        JButton exportProfileButton = new JButton("Export");
        JPanel profileButtonPanel = new JPanel(new GridLayout(7, 1));
        profileButtonPanel.add(addProfileButton);
        profileButtonPanel.add(editProfileButton);
        profileButtonPanel.add(removeProfileButton);
        //profileButtonPanel.add(testProfileButton);
        profileButtonPanel.add(importProfileButton);
        profileButtonPanel.add(exportProfileButton);

        final String[] profileColumnNames = {"Name", "App Key", "Credential Provider"};
        profileTable = new JTable(new DefaultTableModel(profileColumnNames, 0)
        {
            @Override
            public boolean isCellEditable(int row, int column)
            {
                // prevent table cells from being edited. must use dialog to edit.
                return false;
            }
        });

        JScrollPane profileScrollPane = new JScrollPane(profileTable);
        profileScrollPane.setPreferredSize(new Dimension(1000, 200));
        GridBagConstraints c000 = new GridBagConstraints(); c000.gridy = 0; c000.gridwidth = 2; c000.anchor = GridBagConstraints.FIRST_LINE_START;
        GridBagConstraints c001 = new GridBagConstraints(); c001.gridy = 1; c001.gridwidth = 2; c001.anchor = GridBagConstraints.FIRST_LINE_START;
        c001.insets = new Insets(10, 0, 10, 0);
        GridBagConstraints c002 = new GridBagConstraints(); c002.gridy = 2; c002.gridx = 0; c002.anchor = GridBagConstraints.FIRST_LINE_START;
        GridBagConstraints c003 = new GridBagConstraints(); c003.gridy = 2; c003.gridx = 1; c003.anchor = GridBagConstraints.FIRST_LINE_START;
        profilePanel.add(profileLabel, c000);
        profilePanel.add(new JLabel("<html>Add Aliyun API gateway credentials using your <i>appKey</i> and <i>appSecret</i>.</html>"), c001);
        profilePanel.add(profileButtonPanel, c002);
        profilePanel.add(profileScrollPane, c003);

        //
        // custom signed headers table
        //
        JPanel customHeadersPanel = new JPanel(new GridBagLayout());
        JLabel customHeadersLabel = new JLabel("Custom Signed Headers");
        customHeadersLabel.setForeground(textOrange);
        customHeadersLabel.setFont(sectionFont);
        customHeadersOverwriteCheckbox = new JCheckBox("Overwrite existing headers");
        customHeadersOverwriteCheckbox.setToolTipText("Default behavior is to append these headers even if they exist in original request");
        JPanel customHeadersButtonPanel = new JPanel();
        customHeadersButtonPanel.setLayout(new GridLayout(3, 1));
        JButton addCustomHeaderButton = new JButton("Add");
        JButton removeCustomHeaderButton = new JButton("Remove");
        customHeadersButtonPanel.add(addCustomHeaderButton);
        customHeadersButtonPanel.add(removeCustomHeaderButton);

        final String[] headersColumnNames = {"Name", "Value"};
        customHeadersTable = new JTable(new DefaultTableModel(headersColumnNames, 0));
        JScrollPane headersScrollPane = new JScrollPane(customHeadersTable);
        headersScrollPane.setPreferredSize(new Dimension(1000, 150));

        GridBagConstraints c100 = new GridBagConstraints(); c100.gridy = 0; c100.gridwidth = 2; c100.anchor = GridBagConstraints.FIRST_LINE_START;
        GridBagConstraints c101 = new GridBagConstraints(); c101.gridy = 1; c101.gridwidth = 2; c101.anchor = GridBagConstraints.FIRST_LINE_START; c101.insets = new Insets(10, 0, 10, 0);
        GridBagConstraints c102 = new GridBagConstraints(); c102.gridy = 2; c102.gridx = 1; c102.anchor = GridBagConstraints.FIRST_LINE_START;
        GridBagConstraints c103 = new GridBagConstraints(); c103.gridy = 3; c103.gridx = 0; c103.anchor = GridBagConstraints.FIRST_LINE_START;
        GridBagConstraints c104 = new GridBagConstraints(); c104.gridy = 3; c104.gridx = 1; c104.anchor = GridBagConstraints.FIRST_LINE_START;
        customHeadersPanel.add(customHeadersLabel, c100);
        customHeadersPanel.add(new JLabel("Add request headers to be included in the signature. These can be edited in place."), c101);
        customHeadersPanel.add(customHeadersOverwriteCheckbox, c102);
        customHeadersPanel.add(customHeadersButtonPanel, c103);
        customHeadersPanel.add(headersScrollPane, c104);

        //
        // additional headers to sign
        //
        JPanel additionalSignedHeadersPanel = new JPanel(new GridBagLayout());
        JLabel additionalHeadersLabel = new JLabel("Signed Headers");
        additionalHeadersLabel.setForeground(this.textOrange);
        additionalHeadersLabel.setFont(sectionFont);
        additionalSignedHeadersField = new JTextField("", 65);
        GridBagConstraints c200 = new GridBagConstraints(); c200.gridy = 0; c200.gridwidth = 2; c200.anchor = GridBagConstraints.FIRST_LINE_START;
        GridBagConstraints c201 = new GridBagConstraints(); c201.gridy = 1; c201.gridwidth = 2; c201.anchor = GridBagConstraints.FIRST_LINE_START; c201.insets = new Insets(10, 0, 10, 0);
        GridBagConstraints c202 = new GridBagConstraints(); c202.gridy = 2; c202.anchor = GridBagConstraints.FIRST_LINE_START;
        additionalSignedHeadersPanel.add(additionalHeadersLabel, c200);
        additionalSignedHeadersPanel.add(new JLabel("Specify comma-separated header names from the request to include in the signature. Defaults are Host and X-Amz-*"), c201);
        additionalSignedHeadersPanel.add(additionalSignedHeadersField, c202);

        //
        // put it all together
        //
        List<GridBagConstraints> sectionConstraints = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = i;
            c.gridx = 0;
            // add padding in all directions
            c.insets = new Insets(10, 10, 10, 10);
            c.anchor = GridBagConstraints.FIRST_LINE_START;
            c.weightx = 1.0;
            sectionConstraints.add(c);
        }

        JPanel outerPanel = new JPanel(new GridBagLayout());
        outerPanel.add(globalSettingsPanel, sectionConstraints.remove(0));
        GridBagConstraints c = sectionConstraints.remove(0);
        c.fill = GridBagConstraints.HORIZONTAL; // have separator span entire width of display
        outerPanel.add(new JSeparator(SwingConstants.HORIZONTAL), c);
        //outerPanel.add(statusPanel, sectionConstraints.remove(0));
        outerPanel.add(profilePanel, sectionConstraints.remove(0));
        c = sectionConstraints.remove(0);
        c.fill = GridBagConstraints.HORIZONTAL;
        outerPanel.add(new JSeparator(SwingConstants.HORIZONTAL), c);
        outerPanel.add(customHeadersPanel, sectionConstraints.remove(0));
        c = sectionConstraints.remove(0);
        c.fill = GridBagConstraints.HORIZONTAL;
        outerPanel.add(new JSeparator(SwingConstants.HORIZONTAL), c);
        outerPanel.add(additionalSignedHeadersPanel, sectionConstraints.remove(0));

        // use outerOuterPanel to force components north
        JPanel outerOuterPanel = new JPanel(new BorderLayout());
        outerOuterPanel.add(outerPanel, BorderLayout.PAGE_START);
        outerScrollPane = new JScrollPane(outerOuterPanel);
        outerScrollPane.getVerticalScrollBar().setUnitIncrement(18);

        this.callbacks.customizeUiComponent(outerPanel);

        // profile button handlers
        addProfileButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                SigProfileEditorDialog dialog = new SigProfileEditorDialog(null, "Add Profile", true, null);
                callbacks.customizeUiComponent(dialog);
                dialog.setVisible(true);
                // set first profile added as the default
                if (profileNameMap.size() == 1 && dialog.getNewProfileName() != null) {
                    setDefaultProfileName(dialog.getNewProfileName());
                }
            }
        });
        editProfileButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                int[] rowIndeces = profileTable.getSelectedRows();
                if (rowIndeces.length == 1) {
                    DefaultTableModel model = (DefaultTableModel) profileTable.getModel();
                    final String name = (String) model.getValueAt(rowIndeces[0], 0);
                    JDialog dialog = new SigProfileEditorDialog(null, "Edit Profile", true, profileNameMap.get(name));
                    callbacks.customizeUiComponent(dialog);
                    dialog.setVisible(true);
                }
                else {
                    updateStatus("Select a single profile to edit");
                }
            }
        });
        removeProfileButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                DefaultTableModel model = (DefaultTableModel) profileTable.getModel();
                ArrayList<String> profileNames = new ArrayList<>();
                for (int rowIndex : profileTable.getSelectedRows()) {
                    profileNames.add((String) model.getValueAt(rowIndex, 0));
                }
                for (final String name : profileNames) {
                    deleteProfile(profileNameMap.get(name));
                }
            }
        });

        importProfileButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                try {
                    SigProfileImportDialog importDialog = new SigProfileImportDialog(null, "Import Profiles", true);
                    callbacks.customizeUiComponent(importDialog);
                    importDialog.setVisible(true);
                }
                catch (Exception exc) {
                    logger.error("Failed to display import dialog: "+exc);
                }
            }
        });
        exportProfileButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                JFileChooser chooser = new JFileChooser(System.getProperty("user.home"));
                chooser.setFileHidingEnabled(false);
                if (chooser.showOpenDialog(getUiComponent()) == JFileChooser.APPROVE_OPTION) {
                    final Path exportPath = Paths.get(chooser.getSelectedFile().getPath());
                    ArrayList<SigProfile> sigProfiles = new ArrayList<>();
                    for (final String name : profileNameMap.keySet()) {
                        sigProfiles.add(profileNameMap.get(name));
                    }
                    int exportCount = SigProfile.exportToFilePath(sigProfiles, exportPath);
                    final String msg = String.format("Exported %d profiles to %s", exportCount, exportPath);
                    JOptionPane.showMessageDialog(getUiComponent(), formatMessageHtml(msg));
                    logger.info(msg);
                }
            }
        });

        // custom header button handlers
        addCustomHeaderButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                DefaultTableModel model = (DefaultTableModel) customHeadersTable.getModel();
                int i;
                for (i = 0; i < model.getRowCount(); i++) {
                    final String name = ((String) model.getValueAt(i, 0)).trim();
                    if (name.length() == 0) {
                        // do not add more rows if an empty row exists
                        break;
                    }
                }
                if (i == model.getRowCount()) {
                    model.addRow(new Object[]{"", ""});
                }
                customHeadersTable.clearSelection();
                customHeadersTable.addRowSelectionInterval(i, i);
                customHeadersTable.addColumnSelectionInterval(0, 0);
                customHeadersTable.editCellAt(i, 0);
            }
        });
        removeCustomHeaderButton.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                DefaultTableModel model = (DefaultTableModel) customHeadersTable.getModel();
                // remove editor or the table locks up if a cell is being edited
                customHeadersTable.removeEditor();
                // remove rows in reverse order or larger indices will become invalid before removing
                Arrays.stream(customHeadersTable.getSelectedRows())
                        .boxed()
                        .sorted(Comparator.reverseOrder())
                        .forEach(model::removeRow);
            }
        });

        // log level combo box
        class LogLevelComboBoxItem
        {
            final private int logLevel;
            final private String levelName;

            public LogLevelComboBoxItem(final int logLevel)
            {
                this.logLevel = logLevel;
                this.levelName = LogWriter.levelNameFromInt(logLevel);
            }

            @Override
            public String toString()
            {
                return this.levelName;
            }
        }
        this.logLevelComboBox.addItem(new LogLevelComboBoxItem(LogWriter.DEBUG_LEVEL));
        this.logLevelComboBox.addItem(new LogLevelComboBoxItem(LogWriter.INFO_LEVEL));
        this.logLevelComboBox.addItem(new LogLevelComboBoxItem(LogWriter.ERROR_LEVEL));
        this.logLevelComboBox.addItem(new LogLevelComboBoxItem(LogWriter.FATAL_LEVEL));
        this.logLevelComboBox.setSelectedIndex(logger.getLevel());

        this.logLevelComboBox.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                logger.setLevel(((LogLevelComboBoxItem) logLevelComboBox.getSelectedItem()).logLevel);
            }
        });
    }

    public boolean isSigningEnabled()
    {
        return this.signingEnabledCheckBox.isSelected();
    }
    public boolean isInScopeOnlyEnabled() { return this.inScopeOnlyCheckBox.isSelected(); }

    private void setLogLevel(final int level)
    {
        this.logger.setLevel(level);
        // logger is created before UI components are initialized.
        if (this.logLevelComboBox != null) {
            this.logLevelComboBox.setSelectedIndex(logger.getLevel());
        }
    }

    // format a message for display in a dialog. applies reasonable word-wrapping.
    public static String formatMessageHtml(final String msg) {
        return "<html><p style='width: 300px;'>" +
                StringEscapeUtils.escapeHtml4(msg) +
                "</p></html>";
    }

    @Override
    public void registerExtenderCallbacks(IBurpExtenderCallbacks callbacks)
    {
        burpInstance = this;

        this.helpers = callbacks.getHelpers();
        this.callbacks = callbacks;

        callbacks.setExtensionName(EXTENSION_NAME);
        callbacks.registerExtensionStateListener(this);

        this.logger.configure(callbacks.getStdout(), callbacks.getStderr(), LogWriter.DEFAULT_LEVEL);
        final String setting = this.callbacks.loadExtensionSetting(SETTING_LOG_LEVEL);
        if (setting != null) {
            try {
                setLogLevel(Integer.parseInt(setting));
            } catch (NumberFormatException ignored) {
                // use default level
            }
        }

        this.profileKeyIdMap = new HashMap<>();
        this.profileNameMap = new HashMap<>();

        SwingUtilities.invokeLater(new Runnable()
        {
            @Override
            public void run()
            {
                buildUiTab();
                loadExtensionSettings();
                callbacks.addSuiteTab(BurpExtender.this);
                callbacks.registerHttpListener(BurpExtender.this);
                callbacks.registerContextMenuFactory(BurpExtender.this);
                callbacks.registerMessageEditorTabFactory(BurpExtender.this);
                logger.info(String.format("Loaded %s %s", EXTENSION_NAME, EXTENSION_VERSION));
            }
        });
    }

    /*
    build Gson object for de/serialization of settings. SigCredential, SigCredentialProvider, and Path need
    to be handled as a special case since they're interfaces.
     */
    private Gson getGsonSerializer(final double settingsVersion)
    {
        return new GsonBuilder()
                .registerTypeHierarchyAdapter(Path.class, new TypeAdapter<Path>() {
                    @Override
                    public void write(JsonWriter out, Path value) throws IOException {
                        if (value == null)
                            out.nullValue();
                        else
                            out.value(value.toString());
                    }

                    @Override
                    public Path read(JsonReader in) throws IOException {
                        return Paths.get(in.nextString());
                    }
                })
                .setPrettyPrinting() // not necessary...
                .setVersion(settingsVersion)
                //.setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
                .create();
    }

    protected String exportExtensionSettingsToJson()
    {
        ExtensionSettings.ExtensionSettingsBuilder builder = ExtensionSettings.builder()
                .logLevel(this.logger.getLevel())
                .extensionVersion(EXTENSION_VERSION)
                .persistProfiles(this.persistProfilesCheckBox.isSelected())
                .extensionEnabled(this.signingEnabledCheckBox.isSelected())
                .defaultProfileName(this.getDefaultProfileName())
                .customSignedHeaders(getCustomHeadersFromUI())
                .customSignedHeadersOverwrite(this.customHeadersOverwriteCheckbox.isSelected())
                .additionalSignedHeaderNames(getAdditionalSignedHeadersFromUI())
                .inScopeOnly(this.inScopeOnlyCheckBox.isSelected())
                .preserveHeaderOrder(this.advancedSettingsDialog.preserveHeaderOrderCheckBox.isSelected())
                .presignedUrlLifetimeInSeconds(this.advancedSettingsDialog.getPresignedUrlLifetimeSeconds())
                .contentMD5HeaderBehavior(this.advancedSettingsDialog.getContentMD5HeaderBehavior())
                .signingEnabledForSpider(advancedSettingsDialog.signingEnabledForSpiderCheckBox.isSelected())
                .signingEnabledForScanner(advancedSettingsDialog.signingEnabledForScannerCheckBox.isSelected())
                .signingEnabledForIntruder(advancedSettingsDialog.signingEnabledForIntruderCheckBox.isSelected())
                .signingEnabledForRepeater(advancedSettingsDialog.signingEnabledForRepeaterCheckBox.isSelected())
                .signingEnabledForSequencer(advancedSettingsDialog.signingEnabledForSequencerCheckBox.isSelected())
                .signingEnabledForExtender(advancedSettingsDialog.signingEnabledForExtenderCheckBox.isSelected());
        if (this.persistProfilesCheckBox.isSelected()) {
            builder.profiles(this.profileNameMap);
            logger.info(String.format("Saved %d profile(s)", this.profileNameMap.size()));
        }
        ExtensionSettings settings = builder.build();
        return getGsonSerializer(settings.settingsVersion()).toJson(settings);
    }

    protected void importExtensionSettingsFromJson(final String jsonString)
    {
        if (StringUtils.isEmpty(jsonString)) {
            logger.error("Invalid Json settings. Skipping import.");
            return;
        }

        double settingsVersion = 0.0;
        try {
            settingsVersion = Integer.parseInt(callbacks.loadExtensionSetting(SETTING_CONFIG_VERSION));
        } catch (NumberFormatException ignored) {
        }

        ExtensionSettings settings;
        try {
            settings = getGsonSerializer(settingsVersion).fromJson(jsonString, ExtensionSettings.class);
        } catch (JsonParseException exc) {
            logger.error("Failed to parse Json settings. Using defaults.");
            settings = ExtensionSettings.builder().build();
        }

        setLogLevel(settings.logLevel());

        // load profiles
        Map<String, SigProfile> profileMap = settings.profiles();
        for (final String name : profileMap.keySet()) {
            try {
                addProfile(profileMap.get(name));
            } catch (IllegalArgumentException | NullPointerException exc) {
                logger.error("Failed to add profile: "+name);
            }
        }

        setDefaultProfileName(settings.defaultProfileName());
        this.persistProfilesCheckBox.setSelected(settings.persistProfiles());
        this.signingEnabledCheckBox.setSelected(settings.extensionEnabled());
        setCustomHeadersInUI(settings.customSignedHeaders());
        this.customHeadersOverwriteCheckbox.setSelected(settings.customSignedHeadersOverwrite());
        this.additionalSignedHeadersField.setText(String.join(", ", settings.additionalSignedHeaderNames()));
        this.inScopeOnlyCheckBox.setSelected(settings.inScopeOnly());

        final long lifetime = settings.presignedUrlLifetimeInSeconds();
        if (lifetime < ExtensionSettings.PRESIGNED_URL_LIFETIME_MIN_SECONDS || lifetime > ExtensionSettings.PRESIGNED_URL_LIFETIME_MAX_SECONDS) {
            settings = settings.withPresignedUrlLifetimeInSeconds(ExtensionSettings.PRESIGNED_URL_LIFETIME_DEFAULT_SECONDS);
        }

        final String behavior = settings.contentMD5HeaderBehavior();
        if (!Arrays.asList(ExtensionSettings.CONTENT_MD5_REMOVE, ExtensionSettings.CONTENT_MD5_IGNORE, ExtensionSettings.CONTENT_MD5_UPDATE).contains(behavior)) {
            settings = settings.withContentMD5HeaderBehavior(ExtensionSettings.CONTENT_MD5_DEFAULT);
        }

        advancedSettingsDialog.applyExtensionSettings(settings);
    }

    private void saveExtensionSettings()
    {
        // save these with their own key since they may be required before the other settings are loaded
        this.callbacks.saveExtensionSetting(SETTING_LOG_LEVEL, Integer.toString(this.logger.getLevel()));
        this.callbacks.saveExtensionSetting(SETTING_VERSION, EXTENSION_VERSION);
        this.callbacks.saveExtensionSetting(BURP_SETTINGS_KEY, exportExtensionSettingsToJson());
    }

    private void loadExtensionSettings()
    {
        // plugin version that added the settings. in the future use this to migrate settings.
        final String pluginVersion = this.callbacks.loadExtensionSetting(SETTING_VERSION);
        if (pluginVersion != null)
            logger.info("Found settings for version "+pluginVersion);
        else
            logger.info("Found settings for version < 0.2.0");

        final String jsonSettingsString = this.callbacks.loadExtensionSetting(BURP_SETTINGS_KEY);
        if (StringUtils.isEmpty(jsonSettingsString)) {
            logger.info("No plugin settings found");
        }
        else {
            importExtensionSettingsFromJson(jsonSettingsString);
        }
    }

    @Override
    public IMessageEditorTab createNewInstance(IMessageEditorController controller, boolean editable)
    {
        return new SigMessageEditorTab(controller, editable);
    }

    @Override
    public void extensionUnloaded()
    {
        saveExtensionSettings();
        logger.info("Unloading "+EXTENSION_NAME);
    }

    @Override
    public String getTabCaption()
    {
        return DISPLAY_NAME;
    }

    @Override
    public Component getUiComponent()
    {
        return outerScrollPane;
    }

    @Override
    public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation)
    {
        JMenu menu = new JMenu(DISPLAY_NAME);

        // add disable item
        JRadioButtonMenuItem item = new JRadioButtonMenuItem("<html><i>Disabled</i></html>", !isSigningEnabled());
        item.addActionListener(new ActionListener()
        {
            @Override
            public void actionPerformed(ActionEvent actionEvent)
            {
                signingEnabledCheckBox.setSelected(false);
            }
        });
        menu.add(item);

        // insert "auto" profile option
        List<String> profileList = getSortedProfileNames();
        profileList.add(0, NO_DEFAULT_PROFILE); // no default option

        // add all profile names to menu, along with a listener to set the default profile when selected
        for (final String name : profileList) {
            item = new JRadioButtonMenuItem(name, isSigningEnabled() && name.equals(getDefaultProfileName()));
            item.addActionListener(new ActionListener()
            {
                @Override
                public void actionPerformed(ActionEvent actionEvent)
                {
                    JRadioButtonMenuItem item = (JRadioButtonMenuItem) actionEvent.getSource();
                    setDefaultProfileName(item.getText());
                    signingEnabledCheckBox.setSelected(true);
                }
            });
            menu.add(item);
        }

        List<JMenuItem> list = new ArrayList<>();
        list.add(menu);

        // add context menu items
        switch (invocation.getInvocationContext()) {
            case IContextMenuInvocation.CONTEXT_MESSAGE_EDITOR_REQUEST:
            case IContextMenuInvocation.CONTEXT_MESSAGE_VIEWER_REQUEST:
            case IContextMenuInvocation.CONTEXT_PROXY_HISTORY:
                IHttpRequestResponse[] messages = invocation.getSelectedMessages();
                IRequestInfo requestInfo = helpers.analyzeRequest(messages[0]);

                final List<String> authorizationHeaders = requestInfo.getHeaders().stream()
                        .filter(h -> StringUtils.startsWithIgnoreCase(h, "Authorization:"))
                        .collect(Collectors.toList());
                Map<String, String> signature = authorizationHeaders.stream()
                        .map(h -> parseSigV4AuthorizationHeader(h, false))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

                // Add menu item for presigned s3 URLs for GET and PUT
                // XXX: add subitems to get signed url with any profile?
/*                if ((signature != null) && StringUtils.equalsIgnoreCase(signature.get("service"), "s3") &&
                        Arrays.asList("GET", "PUT").contains(requestInfo.getMethod().toUpperCase())) {
                    JMenuItem signedUrlItem = new JMenuItem("Copy Signed URL");
                    signedUrlItem.addActionListener(new ActionListener()
                    {
                        @Override
                        public void actionPerformed(ActionEvent actionEvent)
                        {
                            final SigProfile profile = getSigningProfile(requestInfo.getHeaders());
                            String signedUrl = ""; // clear clipboard on error
                            if (profile == null) {
                                final String msg = "Failed to determine signing profile for presigned URL";
                                logger.error(msg);
                                JOptionPane.showMessageDialog(getUiComponent(), formatMessageHtml(msg));
                            }
                            else {
                                signedUrl = presignRequest(messages[0].getHttpService(), messages[0].getRequest(), profile, advancedSettingsDialog.getPresignedUrlLifetimeSeconds()).toString();
                            }
                            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                            clipboard.setContents(new StringSelection(signedUrl), null);
                        }
                    });
                    list.add(signedUrlItem);
                }*/
                if ((signature == null) && (invocation.getInvocationContext() == IContextMenuInvocation.CONTEXT_MESSAGE_EDITOR_REQUEST)) {
                    JMenu addSignatureMenu = new JMenu("Add Signature");
                    for (final String name : profileList) {
                        if (name.length() == 0 || name.equals(NO_DEFAULT_PROFILE)) continue;
                        JMenuItem sigItem = new JMenuItem(name);
                        sigItem.setActionCommand(name);
                        sigItem.addActionListener(new ActionListener()
                        {
                            @Override
                            public void actionPerformed(ActionEvent actionEvent)
                            {
                                final String profileName = actionEvent.getActionCommand();
                                SigProfile profile = profileNameMap.get(profileName);
                                if (profile == null) {
                                    // this should never happen since the menu is populated with existing profile names
                                    JOptionPane.showMessageDialog(getUiComponent(), formatMessageHtml("Profile name does not exist: "+profileName));
                                    return;
                                }
                                // if region or service is missing from profile, prompt user
  /*                              if (StringUtils.isEmpty(profile.getService()) || StringUtils.isEmpty(profile.getRegion())) {
                                    SigProfileEditorReadOnlyDialog dialog = new SigProfileEditorReadOnlyDialog(null, "Add Signature", true, profile);
                                    callbacks.customizeUiComponent(dialog);
                                    dialog.disableForEdit();
                                    dialog.setVisible(true);
                                    if (dialog.getProfile() == null) {
                                        // user hit "Cancel", abort.
                                        return;
                                    }
                                    profile = dialog.getProfile();
                                }
*/
                                final SigProfile profileCopy = profile; // reference copy is fine here
                                (new Thread(() -> {
                                    try {
                                        // XXX we do some work to prevent custom signed headers specified in the SigV4 UI from
                                        // showing up in the Raw message editor tab to prevent them from being duplicated when
                                        // it's signed again. consider modifying signRequest() to optionally skip adding these.
                                        final byte[] signedRequest = signRequest(messages[0].getHttpService(), messages[0].getRequest(), profileCopy);
                                        if (signedRequest == null || signedRequest.length == 0) {
                                            throw new NullPointerException("Request signing failed for profile: "+profileCopy.getName());
                                        }
                                        //IRequestInfo signedRequestInfo = helpers.analyzeRequest(signedRequest);
/*
                                        // make sure new signature contains a keyId that can be used to automatically select the correct profile
                                        Map<String, String> signature = parseSigV4AuthorizationHeader(signedRequestInfo.getHeaders().stream()
                                                .filter(h -> StringUtils.startsWithIgnoreCase(h, "Authorization:"))
                                                .findFirst().orElse(null), true);
                                        final String accessKeyId = profileCopy.getAccessKeyIdForProfileSelection();
                                        if (accessKeyId != null) {
                                            signature.put("accessKeyId", accessKeyId);
                                        }*/

              /*                          // get original headers minus AWS headers
                                        List<String> allHeaders = requestInfo.getHeaders().stream()
                                                .filter(h -> !StringUtils.startsWithIgnoreCase(h, "Authorization:"))
                                                .filter(h -> !StringUtils.startsWithIgnoreCase(h, "X-Amz-"))
                                                .collect(Collectors.toList());

                                        // add the headers created by signing followed by the modified Authorization header
                                        allHeaders.addAll(signedRequestInfo.getHeaders().stream()
                                                .filter(h -> StringUtils.startsWithIgnoreCase(h, "X-Amz-"))
                                                .collect(Collectors.toList()));
                                        //allHeaders.add(buildSigV4AuthorizationHeader(signature));
                                        allHeaders.add("coraytestheader");
                                        final byte[] body = Arrays.copyOfRange(messages[0].getRequest(), requestInfo.getBodyOffset(), messages[0].getRequest().length);
                                        messages[0].setRequest(helpers.buildHttpMessage(allHeaders, body));*/
                                        messages[0].setRequest(signedRequest);
                                    } catch (IllegalArgumentException | NullPointerException exc) {
                                        JOptionPane.showMessageDialog(getUiComponent(), formatMessageHtml("Failed to add signature: " + exc.getMessage()));
                                    }
                                })).start();
                            }
                        });
                        addSignatureMenu.add(sigItem);
                    }
                    list.add(addSignatureMenu);
                }
                else if ((signature != null) && (invocation.getInvocationContext() == IContextMenuInvocation.CONTEXT_MESSAGE_EDITOR_REQUEST)) {
                    JMenuItem editSignatureItem = new JMenuItem("Edit Signature");
                    editSignatureItem.addActionListener(new ActionListener()
                    {
                        @Override
                        public void actionPerformed(ActionEvent actionEvent)
                        {
                            SigProfile signingProfile = authorizationHeaders.stream()
                                    .map(BurpExtender.this::profileFromAuthorizationHeader)
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElse(null);

                            SigProfileEditorReadOnlyDialog dialog = new SigProfileEditorReadOnlyDialog(
                                    null, "Edit Signature", true,
                                    (signingProfile != null) ? signingProfile : new SigProfile.Builder("TEMP").build());

                           /* if (signingProfile == null) {
                                // populate Add Profile dialog with some defaults taken from the Authorization header
                                dialog.nameTextField.setText(" ");
                                dialog.profileKeyIdTextField.setText(signature.get("accessKeyId"));
                            }

                            if (StringUtils.isNotEmpty(signature.get("service")))
                                dialog.serviceTextField.setText(signature.get("service"));
                            if (StringUtils.isNotEmpty(signature.get("region")))
                                dialog.regionTextField.setText(signature.get("region"));*/
                            callbacks.customizeUiComponent(dialog);
                            dialog.disableForEdit();
                            //dialog.focusEmptyField();
                            dialog.setVisible(true);
                            signingProfile = dialog.getProfile();

                            if (signingProfile != null) {
                                // preserve header order by getting index of first Authorization header
                                final List<String> allHeaders = requestInfo.getHeaders();
                                final int insertIndex = IntStream.range(0, allHeaders.size())
                                        .filter(i -> StringUtils.startsWithIgnoreCase(allHeaders.get(i), "Authorization:"))
                                        .findFirst()
                                        .orElse(1);

                                List<String> nonAuthHeaders = requestInfo.getHeaders().stream()
                                        .filter(h -> !StringUtils.startsWithIgnoreCase(h, "Authorization:"))
                                        .collect(Collectors.toList());
                                final byte[] body = Arrays.copyOfRange(messages[0].getRequest(), requestInfo.getBodyOffset(), messages[0].getRequest().length);
                                signature.put("accessKeyId", signature.getOrDefault("accessKeyId", signingProfile.getAccessKeyId()));
/*                                signature.put("region", signingProfile.getRegion());
                                signature.put("service", signingProfile.getService());*/
                                nonAuthHeaders.add(insertIndex, buildSigV4AuthorizationHeader(signature));
                                messages[0].setRequest(helpers.buildHttpMessage(nonAuthHeaders, body));
                            }
                        }
                    });
                    list.add(editSignatureItem);
                }
        }
        return list;
    }

    // check Authorization header for AccessKeyId and return matching profile or null.
    private SigProfile profileFromAuthorizationHeader(final String header) {
        return Stream.of(header)
                .map(h -> parseSigV4AuthorizationHeader(h, false))
                .filter(Objects::nonNull)
                .filter(a -> this.profileKeyIdMap.containsKey(a.get("accessKeyId")))
                .map(a -> this.profileKeyIdMap.get(a.get("accessKeyId")))
                .findFirst()
                .orElse(null);
    }

    private static Map<String, String> parseSigV4AuthorizationHeader(final String header, final boolean strict)
    {
        Map<String, String> signature = null;
        Pattern pattern = authorizationHeaderLooseRegex;
        if (strict) {
            pattern = authorizationHeaderRegex;
        }
        Matcher matcher = pattern.matcher(header);
        if (matcher.matches()) {
            signature = new HashMap<>(Map.of(
                    "accessKeyId", matcher.group("accessKeyId"),
                    "date", matcher.group("date"),
                    "region", matcher.group("region"),
                    "service", matcher.group("service"),
                    "headers", matcher.group("headers"),
                    "signature", matcher.group("signature")
            ));
        }
        return signature;
    }

    private static String buildSigV4AuthorizationHeader(final Map<String, String> signature) {
        return String.format(
                "X-Ca-Signature: AWS4-HMAC-SHA256 Credential=%s/%s/%s/%s/aws4_request, SignedHeaders=%s, Signature=%s",
                signature.get("accessKeyId"), signature.get("date"), signature.get("region"), signature.get("service"),
                signature.get("headers"), signature.get("signature"));
    }

    // display status message in UI
    private void updateStatus(final String status)
    {
        logger.debug("Set Status: " + status);
        this.statusLabel.setText(status);
    }

    private List<String> getSortedProfileNames()
    {
        // sort by name in table
        List<String> profileNames = new ArrayList<>(this.profileNameMap.keySet());
        Collections.sort(profileNames);
        return profileNames;
    }

    /*
    call this when profile list changes
    */
    private void updateAwsProfilesUI()
    {
        DefaultTableModel model = (DefaultTableModel) this.profileTable.getModel();
        model.setRowCount(0); // clear table
        final String defaultProfileName = (String) defaultProfileComboBox.getSelectedItem();
        defaultProfileComboBox.removeAllItems();
        defaultProfileComboBox.addItem(NO_DEFAULT_PROFILE);

        for (final String name : getSortedProfileNames()) {
            SigProfile profile = this.profileNameMap.get(name);
            model.addRow(new Object[]{profile.getName(), profile.getAccessKeyIdForProfileSelection(), profile.getSecretKeyForProfileSelection()});
            defaultProfileComboBox.addItem(name);
        }
        setDefaultProfileName(defaultProfileName);
    }

    /*
    NOTE: this will overwrite an existing profile with the same name
    */
    protected void addProfile(final SigProfile profile)
    {
        final SigProfile p1 = this.profileNameMap.get(profile.getName());
        if (p1 == null) {
            // profile name doesn't exist. make sure there is no keyId conflict with an existing profile
            if (profile.getAccessKeyIdForProfileSelection() != null) {
                SigProfile p2 = this.profileKeyIdMap.get(profile.getAccessKeyIdForProfileSelection());
                if (p2 != null) {
                    // keyId conflict. do not add profile
                    updateStatus("Profiles must have a unique accessKeyId: "+profile.getName());
                    throw new IllegalArgumentException(String.format("Profiles must have a unique accessKeyId: %s = %s", profile.getName(), p2.getName()));
                }
            }
        }

        this.profileNameMap.put(profile.getName(), profile);

        // refresh the keyId map
        this.profileKeyIdMap.clear();
        for (final SigProfile p : this.profileNameMap.values()) {
            if (p.getAccessKeyIdForProfileSelection() != null) {
                this.profileKeyIdMap.put(p.getAccessKeyIdForProfileSelection(), p);
            }
        }

        updateAwsProfilesUI();
        if (p1 == null) {
            updateStatus("Added profile: " + profile.getName());
        }
        else {
            updateStatus("Saved profile: " + profile.getName());
        }
    }

    /*
    if newProfile is valid, delete oldProfile and add newProfile.
     */
    protected void updateProfile(final SigProfile oldProfile, final SigProfile newProfile)
    {
        if (oldProfile == null) {
            addProfile(newProfile);
            return;
        }

        // remove any profile with same name
        final SigProfile p1 = this.profileNameMap.get(oldProfile.getName());
        if (p1 == null) {
            updateStatus("Update profile failed. Old profile doesn't exist.");
            throw new IllegalArgumentException("Update profile failed. Old profile doesn't exist.");
        }

        // if we are updating the default profile, ensure it remains the default
        final boolean defaultProfileUpdated = getDefaultProfileName().equals(oldProfile.getName());

        deleteProfile(oldProfile);
        try {
            addProfile(newProfile);
            if (defaultProfileUpdated) {
                setDefaultProfileName(newProfile.getName());
            }
        } catch (IllegalArgumentException exc) {
            addProfile(oldProfile); // oops. add old profile back
            throw exc;
        }
    }

    private void deleteProfile(SigProfile profile)
    {
        if (this.profileNameMap.containsKey(profile.getName())) {
            this.profileNameMap.remove(profile.getName());
            updateStatus(String.format("Deleted profile '%s'", profile.getName()));
        }
        if (profile.getAccessKeyIdForProfileSelection() != null) {
            this.profileKeyIdMap.remove(profile.getAccessKeyIdForProfileSelection());
        }
        updateAwsProfilesUI();
    }

    /*
    Check if the request is signed with SigV4. Not a strict check.
    This routine needs to be fast since potentially ALL requests will cause an invocation.
    */
    public static boolean isAws4Request(final IRequestInfo request)
    {
        if (request.getHeaders().stream().anyMatch(h -> h.equalsIgnoreCase(SKIP_SIGNING_HEADER))) {
            return false;
        }
        return request.getHeaders().stream().anyMatch(h -> authorizationHeaderLooseNoCaptureRegex.matcher(h).matches());
    }

    public static boolean isAws4PreSignedRequest(final IRequestInfo request) {
        return request.getParameters().stream().filter(p -> p.getType() == IParameter.PARAM_URL).anyMatch(p -> StringUtils.equalsIgnoreCase(p.getName(), "X-Amz-Credential"));
    }

    private String getDefaultProfileName()
    {
        String defaultProfileName = (String) this.defaultProfileComboBox.getSelectedItem();
        if (defaultProfileName == null) {
            defaultProfileName = NO_DEFAULT_PROFILE;
        }
        return defaultProfileName;
    }

    /*
    Note that no check is done on profile name. It is assumed values come from SigProfile and are validated there.
     */
    private void setDefaultProfileName(final String defaultProfileName)
    {
        if (defaultProfileName != null) {
            for (int i = 0; i < this.defaultProfileComboBox.getItemCount(); i++) {
                if (this.defaultProfileComboBox.getItemAt(i).equals(defaultProfileName)) {
                    this.defaultProfileComboBox.setSelectedIndex(i);
                    //updateStatus("Default profile changed.");
                    return;
                }
            }
        }
        // possible if persistProfiles was set to false and default profile was not saved
    }

    private List<String> getAdditionalSignedHeadersFromUI()
    {
        return Arrays.asList(additionalSignedHeadersField.getText().split(",+"))
                .stream()
                .map(String::trim)
                .filter(h -> h.length() > 0)
                .collect(Collectors.toList());
    }

    /* get the additional headers specified in the UI */
    private List<String> getCustomHeadersFromUI()
    {
        List<String> headers = new ArrayList<>();
        DefaultTableModel model = (DefaultTableModel) customHeadersTable.getModel();
        for (int i = 0; i < model.getRowCount(); i++) {
            final String name = ((String) model.getValueAt(i, 0)).trim();
            final String value = ((String) model.getValueAt(i, 1)).trim();
            if (name.length() > 0) { // skip empty header names
                headers.add(String.format("%s: %s", name, value));
            }
        }
        return headers;
    }

    private void setCustomHeadersInUI(final List<String> customHeaders)
    {
        DefaultTableModel model = (DefaultTableModel) customHeadersTable.getModel();
        model.setRowCount(0);
        for (final String header : customHeaders) {
            final String[] tokens = header.split("[\\s:]+");
            if (tokens.length == 1) {
                model.addRow(new Object[]{tokens[0], ""});
            }
            else {
                model.addRow(new Object[]{tokens[0], tokens[1]});
            }
        }
    }

    public SigProfile getSigningProfile(final List<String> headers)
    {
        // check for http header that specifies a signing profile. if not specified in the header,
        // use the default profile. lastly, check Authorization header for an accessKeyId that matches
        // an existing profile.
        // XXX if a non-existent profile is specified in the header, error out?
        SigProfile signingProfile = headers.stream()
                .filter(h -> StringUtils.startsWithIgnoreCase(h, PROFILE_HEADER_NAME+":"))
                .map(h -> this.profileNameMap.get(splitHeader(h)[1]))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(this.profileNameMap.get(getDefaultProfileName()));

        if (signingProfile == null) {
            signingProfile = headers.stream()
                    .map(this::profileFromAuthorizationHeader)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        return signingProfile;
    }

    /*
     Always returns an array of size 2 even if value is empty string.
     Name and value are trimmed of whitespace.
     */
    public static String[] splitHeader(final String header)
    {
        List<String> tokens = Arrays.stream(header.split(":", 2))
                .map(String::trim)
                .collect(Collectors.toList());
        if (tokens.size() < 2) {
            return new String[]{tokens.get(0), ""};
        }
        return new String[]{tokens.get(0), tokens.get(1)};
    }


    public byte[] signRequest(final IHttpService httpService, final byte[] originalRequestBytes, final SigProfile signingProfile)
    {
        IRequestInfo request = helpers.analyzeRequest(httpService, originalRequestBytes);

        List<String> finalHeaders = new ArrayList<>();

        List<String> allHeaders = request.getHeaders();

        //
        final String lineOne = allHeaders.get(0);

        String appSecret = signingProfile.getSecretKey();

        String reqPath = lineOne.split(" ")[1];
        String reqMethod = lineOne.split(" ")[0];
        String reqHost = "invaild.host.name";
        String reqContentType = "application/json";
        String acpContentType = "*/*";

        for(String item:allHeaders){
            if (item.toLowerCase().contains("host")){
                reqHost = item.substring(6);
            }
            if (item.toLowerCase().contains("content-type")){
                reqContentType = item.substring(14);
            }
            if (item.toLowerCase().contains("accept-type")){
                reqContentType = item.substring(13);
            }
        }

        Map<String , String> headerParams = new HashMap<>();
        //headerParams.put("Accept" , "*/*");


        String httpSchema = "";

        headerParams = null;

        final byte[] body = Arrays.copyOfRange(originalRequestBytes, request.getBodyOffset(), originalRequestBytes.length);

        finalHeaders = HttpUtil.buildHttpRequest(appSecret, httpSchema ,reqMethod , reqHost , reqPath , null , null , null  , body , reqContentType , acpContentType , headerParams);
        logger.debug("======= final header ==========\n"+finalHeaders.toString());

        Set<String> signedHeaderSet = getAdditionalSignedHeadersFromUI().stream().map(String::toLowerCase).collect(Collectors.toSet());


        finalHeaders.add(0, lineOne);
        final byte[] requestBytes = helpers.buildHttpMessage(finalHeaders, body);
        logger.debug("=======SIGNED REQUEST==========\n"+helpers.bytesToString(requestBytes));
        logger.debug("=======END REQUEST=============");
        return requestBytes;
    }
/*
    public URL presignRequest(final IHttpService httpService, final byte[] originalRequestBytes, final SigProfile signingProfile, final long durationSeconds)
    {

        IRequestInfo request = helpers.analyzeRequest(httpService, originalRequestBytes);
        // parse authorization header
*//*        String region = "";
        String service = "";*//*

        for (final IParameter parameter : request.getParameters()) {
            if (parameter.getType() == IParameter.PARAM_URL) {
                if (parameter.getName().equalsIgnoreCase("X-Amz-Credential")) {
                    Matcher matcher = xAmzCredentialRegex.matcher(helpers.urlDecode(parameter.getValue()));
                    if (matcher.matches()) {
                        region = matcher.group("region");
                        service = matcher.group("service");
                        break;
                    }
                }
            }
        }

        if (region.equals("") || service.equals("")) {
            for (final String header : request.getHeaders()) {
                Matcher matcher = authorizationHeaderRegex.matcher(header);
                if (matcher.matches()) {
                    region = matcher.group("region");
                    service = matcher.group("service");
                    break;
                }
            }
        }

        SigCredential credential;
        try {
            credential = signingProfile.getCredential();
        } catch (SigCredentialProviderException exc) {
            logger.error("During request signing: "+exc.getMessage());
            return null;
        }

        AwsCredentials awsCredentials;
        if (credential.isTemporary()) {
            awsCredentials = AwsSessionCredentials.create(credential.getAccessKeyId(), credential.getSecretKey(), ((SigTemporaryCredential) credential).getSessionToken());
        }
        else {
            awsCredentials = AwsBasicCredentials.create(credential.getAccessKeyId(), credential.getSecretKey());
        }

        // if region or service are specified in the profile, override them from original request
//        if (StringUtils.isNotEmpty(signingProfile.getRegion())) {
//            region = signingProfile.getRegion();
//        }
//        if (StringUtils.isNotEmpty(signingProfile.getService())) {
//            service = signingProfile.getService();
//        }

        // build request object for signing
        URI uri;
        try {
            uri = request.getUrl().toURI();
        } catch (URISyntaxException exc) {
            logger.error("Bad URL for signature: "+request.getUrl());
            return null;
        }

        final byte[] body = Arrays.copyOfRange(originalRequestBytes, request.getBodyOffset(), originalRequestBytes.length);

        final SdkHttpFullRequest awsRequest = SdkHttpFullRequest.builder()
                .uri(uri)
                .method(SdkHttpMethod.fromValue(request.getMethod()))
                .contentStreamProvider(() -> new ByteArrayInputStream(body))
                .build();

        // sign the request. can throw IllegalArgumentException
        SdkHttpFullRequest signedRequest;
        Aws4PresignerParams signerParams = Aws4PresignerParams.builder()
                .awsCredentials(awsCredentials)
                .doubleUrlEncode(shouldDoubleUrlEncodeForService(service))
                .signingRegion(Region.of(region))
                .signingName(service)
                .expirationTime(Instant.now().plusSeconds(durationSeconds))
                .build();
        if (StringUtils.equalsIgnoreCase(service, "s3")) {
            signedRequest = AwsS3V4Signer.create().presign(awsRequest, signerParams);
        }
        else {
            signedRequest = Aws4Signer.create().presign(awsRequest, signerParams);
        }

        try {
            return signedRequest.getUri().toURL();
        } catch (MalformedURLException exc) {
            logger.error("Invalid presigned URL: "+signedRequest.getUri().toASCIIString());
        }
        return null;
    }*/

    private boolean isSigningEnabledForTool(final int toolFlag)
    {
        switch (toolFlag) {
            case IBurpExtenderCallbacks.TOOL_PROXY:
                return advancedSettingsDialog.signingEnabledForProxyCheckbox.isSelected();
            case IBurpExtenderCallbacks.TOOL_SPIDER:
                return advancedSettingsDialog.signingEnabledForSpiderCheckBox.isSelected();
            case IBurpExtenderCallbacks.TOOL_SCANNER:
                return advancedSettingsDialog.signingEnabledForScannerCheckBox.isSelected();
            case IBurpExtenderCallbacks.TOOL_INTRUDER:
                return advancedSettingsDialog.signingEnabledForIntruderCheckBox.isSelected();
            case IBurpExtenderCallbacks.TOOL_REPEATER:
                return advancedSettingsDialog.signingEnabledForRepeaterCheckBox.isSelected();
            case IBurpExtenderCallbacks.TOOL_SEQUENCER:
                return advancedSettingsDialog.signingEnabledForSequencerCheckBox.isSelected();
            case IBurpExtenderCallbacks.TOOL_EXTENDER:
                return advancedSettingsDialog.signingEnabledForExtenderCheckBox.isSelected();
            default:
                return false;
        }
    }

    @Override
    public void processHttpMessage(int toolFlag, boolean messageIsRequest, IHttpRequestResponse messageInfo)
    {
        IRequestInfo request = null;

        // Strip any headers used internally by the extension. When using the aws sdk, our http client adds a header
        // to communicate to the extension that the request should be ignored. For example, when using the "Test"
        // button, we send a request to sts:GetCallerIdentity and it is important that the original signature is not modified.
        if (messageIsRequest && toolFlag == IBurpExtenderCallbacks.TOOL_EXTENDER) {
            request = helpers.analyzeRequest(messageInfo);
            if (request.getHeaders().stream().anyMatch(h -> StringUtils.equalsIgnoreCase(h, SKIP_SIGNING_HEADER))) {
                // Found skip header
                messageInfo.setRequest(helpers.buildHttpMessage(
                        request.getHeaders().stream().filter(h -> !StringUtils.startsWithIgnoreCase(h, HEADER_PREFIX)).collect(Collectors.toList()),
                        Arrays.copyOfRange(messageInfo.getRequest(), request.getBodyOffset(), messageInfo.getRequest().length)
                ));
                return;
            }
        }

        if (messageIsRequest && signingEnabledCheckBox.isSelected() && isSigningEnabledForTool(toolFlag)) {
            if (request == null) {
                request = helpers.analyzeRequest(messageInfo);
            }

            // check request scope
            if (this.inScopeOnlyCheckBox.isSelected() && !this.callbacks.isInScope(request.getUrl())) {
                logger.debug("Skipping out of scope request: " + request.getUrl());
                return;
            }

            if (isAws4Request(request)) {
                final SigProfile signingProfile = getSigningProfile(request.getHeaders());

                if (signingProfile == null) {
                    logger.error("Failed to get signing profile");
                    return;
                }

                final byte[] requestBytes = signRequest(messageInfo.getHttpService(), messageInfo.getRequest(), signingProfile);
                if (requestBytes != null) {
                    messageInfo.setRequest(requestBytes);
                    messageInfo.setComment(DISPLAY_NAME+" "+signingProfile.getName());
                }
                else {
                    callbacks.issueAlert(String.format("Failed to sign with profile \"%s\". See Extender log for details.", signingProfile.getName()));
                }
            }
        }
    }

}

