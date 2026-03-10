# Health Connect Permissions Declaration

Draft answers for Google Play's Health Connect permissions declaration form.

## App Description and Core Functionality

Health Disconnect is a privacy-focused health data viewer that reads data from Health Connect and displays it as interactive charts and home screen widgets. The app operates entirely offline with no internet permission, no user accounts, and no data collection.

## Requested Permissions and Justification

### Read Permissions (all READ-only — the app never writes to Health Connect)

| Permission | Justification |
|------------|---------------|
| Steps | Display step count trends in charts and widgets |
| Distance | Display distance trends in charts and widgets |
| Speed | Display speed data in charts and widgets |
| Exercise Sessions | Display workout data in charts and widgets |
| Floors Climbed | Display floors climbed trends in charts and widgets |
| Elevation Gained | Display elevation data in charts and widgets |
| Power | Display power output data in charts and widgets |
| Wheelchair Pushes | Display wheelchair push data in charts and widgets |
| Heart Rate | Display heart rate trends in charts and widgets |
| Resting Heart Rate | Display resting heart rate trends in charts and widgets |
| Heart Rate Variability | Display HRV trends in charts and widgets |
| Blood Pressure | Display blood pressure trends in charts and widgets |
| Oxygen Saturation | Display SpO2 trends in charts and widgets |
| VO2 Max | Display VO2 max trends in charts and widgets |
| Weight | Display weight trends in charts and widgets |
| Height | Display height data in charts and widgets |
| Body Fat | Display body fat percentage trends in charts and widgets |
| Lean Body Mass | Display lean body mass trends in charts and widgets |
| Bone Mass | Display bone mass trends in charts and widgets |
| Body Water Mass | Display body water mass trends in charts and widgets |
| Basal Metabolic Rate | Display BMR trends in charts and widgets |
| Body Temperature | Display body temperature trends in charts and widgets |
| Basal Body Temperature | Display basal body temperature trends in charts and widgets |
| Hydration | Display hydration trends in charts and widgets |
| Nutrition | Display nutrition data in charts and widgets |
| Active Calories Burned | Display active calorie trends in charts and widgets |
| Total Calories Burned | Display total calorie trends in charts and widgets |
| Respiratory Rate | Display respiratory rate trends in charts and widgets |
| Sleep | Display sleep data in charts and widgets |
| Menstruation Flow | Display menstruation data in charts and widgets |
| Cervical Mucus | Display cervical mucus data in charts and widgets |
| Ovulation Test | Display ovulation test data in charts and widgets |
| Intermenstrual Bleeding | Display intermenstrual bleeding data in charts and widgets |
| Sexual Activity | Display sexual activity data in charts and widgets |
| Read Health Data History | Access historical health data beyond the default 30-day window |
| Read Health Data in Background | Refresh home screen widget data without the app being in the foreground |

### Write Permissions
None. Health Disconnect never writes data to Health Connect.

## How Health Data Is Handled

- **Storage:** Health Connect data is read on demand and cached in a local Room database for chart rendering and widget display. The cache is stored in the app's private storage directory.
- **Network:** The app contains no INTERNET permission. Health data never leaves the device through network requests.
- **Sharing:** Users may explicitly export aggregated data as CSV or share chart images via the system share sheet. The app warns users before any data leaves the app.
- **Third parties:** No health data is shared with any third party, SDK, or analytics service.
- **Retention:** Cached data is cleared when the user uninstalls the app or clears app data.

## Privacy Policy URL
https://monkopedia.github.io/health-disconnect/privacy-policy.html

## App Category
Health & Fitness — data visualization and tracking tool

## Video Demonstration
Not yet recorded. The app reads Health Connect data and displays it in charts; a screen recording can be provided if required.
