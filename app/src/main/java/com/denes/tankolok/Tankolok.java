package com.denes.tankolok;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.StrictMode;
import android.util.Log;
import android.widget.RemoteViews;
import android.widget.Toast;

import junit.framework.Assert;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

/**
 * TANKOLOK WIDGET
 * <p>
 * Legolcsóbb, közelben lévő üzemanyag töltő állomás keresését segítő alkalmazás. :)
 *
 * @Author Rapcsák Dénes GQQUBN (UI, Java)
 * @Mentor Istenes Imre (only Code review and tips)
 * <p>
 * Bővíthetőségi ötletek:
 * - Üzemanyag fajták szűrése
 * - Távolság beállítása (most fix 5km).
 * - Kutak egyéni szűrése.
 * - Kiválasztott kúthoz GPS navigáció? (külső alkalmazás)
 * - Magyarországon kívül?
 * <p>
 * <p>
 * TODO-k:
 * (104, 12) // TODO: üzenetet kiírni!
 * (240, 12) // TODO: Más szerinti rendezés
 * (280, 16) // TODO: Számok  helyett piros-zöld gomb?
 * (407, 16) // TODO: Force gps update?
 */
public class Tankolok extends AppWidgetProvider {

    // =======================
    // ===== KONSTANSOK ======
    // =======================

    // Appnév, loghoz
    private static final String TAG = "Tankolok";

    // Kirandó adatok között eltelt idő (ezredmásodperc)
    private static final int sleepTimeBeetweenInfosMS = 3000;

    // ========================
    // === OSZTÁLY VÁLTOZÓK ===
    // ========================

    // ToltoAllomas-okat tároló objektum
    private ArrayList<ToltoAllomas> toltoAllomasList = new ArrayList<>();

    // Helyzet meghatározó manager.
    private LocationManager locationManager;

    // Hiba esetén üzenet tároló objektum
    private String devMessage = "";

    // Legjobb pontosság mértéke (méter?)
    private float bestAccurate = 0;

    // ========================
    // ===== MEGVALÓSÍTÁS =====
    // ========================

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {

        Log.d(TAG, "Update started");

        // WidgetID
        int widgetId = appWidgetIds[0];

        // Lazább szabályok
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder().permitAll().build());

        // =======================
        // ====== NET CHECK ======
        // =======================

        // TODO: üzenetet kiírni!
        if (!isOnline(context)) {
            Toast.makeText(context.getApplicationContext(), "Nincs net!", Toast.LENGTH_LONG).show();
            devMessage = "Nincs net!";
            Log.d(TAG, "ERROR - " + devMessage);
        }

        // ============================
        // ====== LOCATION CHECK ======
        // =============================

        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        // NOTE: Nem megbízható (néha location = null, és a force se stabil)
        //String bestProvider = locationManager.getBestProvider(new Criteria(), false);
        //Location location = locationManager.getLastKnownLocation(bestProvider);

        // Megbízhatóbb lokalizáció betöltése.
        Location location = getLastKnownLocation();


        if (null == location) {
            Toast.makeText(context.getApplicationContext(), "Nincs lokalizáció!", Toast.LENGTH_LONG).show();
            devMessage += " Nincs lokalizáció!";
        }


        // Lokalizációs adatok kiolvasása
        Double lat = 0.0, lon = 0.0;
        String cityName = null;
        try {
            lat = location.getLatitude();
            lon = location.getLongitude();
            Geocoder gcd = new Geocoder(context,
                    Locale.getDefault());
            List<Address> addresses;

            addresses = gcd.getFromLocation(lat, lon, 1);
            if (addresses.size() > 0)
                System.out.println(addresses.get(0).getLocality());
            cityName = addresses.get(0).getLocality();

        } catch (NullPointerException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // ===============================
        // ====== KUTAK LEKÉRDEZÉSE ======
        // ===============================

        //DEBUG
        //System.out.println(lon + "\n" + lat+"My Currrent City is: " + cityName);

        try {

            // ====== URL betöltése ======

            URL url = new URL("" +
                    "https://holtankoljak.hu/ajax.php?a=search" + //
                    "&view=table" + //
                    "&fuelTypeID=1&" + //
                    "&lat=" + lat +//
                    "&lng=" + lon +//
                    "&range=5" +//
                    "&subpage_path=uzemanyagok"); //

            BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));

            String inputLine;
            StringBuffer buff = new StringBuffer();

            // Soronként felöltés
            while ((inputLine = in.readLine()) != null) {
                buff.append(inputLine);
            }

            // Buffer zárása
            if (null != in) {
                in.close();
            }


            // ====== JSON feldolgozása ======


            // JSON feldolgozása -> ToltoAllomas obj Listába
            String result = buff.toString();
            JSONObject reader = new JSONObject(result);
            JSONArray markers = reader.getJSONArray("markers");
            JSONObject c;

            // Lista törlése! Frissítés gombnál felgyűlne a sok duplikátum, így ugyanaz lenne az első X találat.
            toltoAllomasList.clear();

            // Kiemelve --> kevesebb memória
            ToltoAllomas allomas = null;


            // ====== Lista feltöltése ToltoAllomas objektumokkal ======

            for (int j = 0; j < markers.length(); j++) {
                c = markers.getJSONObject(j);
                Double price = c.getDouble("price");

                // Ha a price 0.0=Ismeretlen vagy egyéb baj van, akkor nem írjuk fel.
                if (price < 10) {

                    // Nem írjuk fel, kövi iteráció
                    continue;
                }

                // Új objektum létrehozása-feltöltése
                allomas = new ToltoAllomas(price, c.getString("name"), c.getDouble("distance"), c.getString("address"));

                //
                // LISTA
                //
                // Hozzáadás a listához
                toltoAllomasList.add(allomas);

                Log.d(TAG, "New ToltoAllomas: " + allomas.toString());
            }


        } catch (Exception e) {
            e.printStackTrace();
            Log.d(TAG, "Unable to get data from holtankoljak.hu", e);
        }


        // ======================
        // ====== RENDEZÉS ======
        // ======================

        // TODO: Más szerinti rendezés

        // Rendezés ár szerint
        Collections.sort(toltoAllomasList);

        // =========================
        // ====== HIBAKERESÉS ======
        // =========================

        if (toltoAllomasList.size() < 3 && devMessage.length() == 0) {
            // Anomália, de ez megoldja
            devMessage += " Nincsennek adatok!";
        }

        // ======================
        // ====== SZŰRÉS ========
        // ======================

        // Ha volt hiba, akkor azt jelenítjuk meg egy az egyik field-ben
        // 2 = 3 mert 0,1,2
        int max = 2;

        // HA van hibaüzenet, nem lesz ciklus
        if (!devMessage.isEmpty()) {
            max = 0;
        }

        // ===================================
        // ====== NÉZET FELTÖLTÖLTÉSE ========
        // ===================================

        for (int i = max; i >= 0; i--) {

            RemoteViews remoteViews = new RemoteViews(context.getPackageName(),
                    R.layout.simple_widget);

            Calendar c = new GregorianCalendar();

            // DEV infó - utolsó frissítés ideje
            // Ha nyomogatva van a frissítés gomb, látszódjon hogy tényleg történik-e valami
            // TODO: Számok  helyett piros-zöld gomb?
            String time = c.get(Calendar.HOUR) + ":" + c.get(Calendar.MINUTE) + ":" + c.get(Calendar.SECOND);

            // === HIBA KIÉRTÉKELÉS ===

            if (devMessage.isEmpty()) {

                // NINCS HIBA

                ToltoAllomas tmpToltoAllomas = toltoAllomasList.get(i);
                Log.d(TAG, i + ". SORTED ToltoAllomas: " + tmpToltoAllomas == null ? "null!" : tmpToltoAllomas.toString());

                remoteViews.setTextViewText(R.id.price, "" + tmpToltoAllomas.getPrice() + " Ft - " + toltoAllomasList.get(i).getName());
                remoteViews.setTextViewText(R.id.distance, "" + tmpToltoAllomas.getDistance() + "m, (" + ((int) (location.getAccuracy())) + "m)");
                remoteViews.setTextViewText(R.id.address, tmpToltoAllomas.getAddress());
                // DEBUG
                //remoteViews.setTextViewText(R.id.myloc, "" + myLocal);
                remoteViews.setTextViewText(R.id.info, "" + (i + 1) + ". " + time);
            } else {

                // VAN HIBA

                remoteViews.setTextViewText(R.id.info, devMessage + " " + time);
                remoteViews.setTextViewText(R.id.price, "Hiba! Bocs");
                remoteViews.setTextViewText(R.id.address, "");
                remoteViews.setTextViewText(R.id.distance, "");
            }

            // === INTENT FELTÖLTÉSE ===

            // Ezek olyan Intent objektumok, amelyek az üzenet tartalmát hordozzák. Activityk és Servizek aktiválásakor
            // Az intentek műveletek absztrakt leírásai, összekötők a <bármik> közt.
            Intent intent = new Intent(context, Tankolok.class);

            // ACTION = UPDATE
            intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                    0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

            // refresh gombot megnyomva aktiválódik
            remoteViews.setOnClickPendingIntent(R.id.refresh, pendingIntent);

            // =====================
            // ====== UPDATE =======
            // =====================

            appWidgetManager.updateAppWidget(widgetId, remoteViews);

            Log.d(TAG, "Update " + i + ". view");

            // Ha volt hiba, nem megyünk tovább a ciklisban semmiképp
            // NOTE: Törölhető nemsoká
            if (!devMessage.isEmpty()) {
                break;
            }
            // Várunk egy kicsit, hogy legyen idő elolvasni.
            sleep(sleepTimeBeetweenInfosMS);

            Log.d(TAG, "Sleep " + sleepTimeBeetweenInfosMS + " ms");

        }
        if (devMessage.isEmpty()) {
            Log.d(TAG, "Update succesfully ended");
        } else {
            Log.d(TAG, "Uuups, whe have some errors.");
        }

        devMessage = "";

    }

    // ===============================================
    // ======== S E G É D F Ü G G V É N Y E K ========
    // ===============================================

    /**
     * BIZTONSÁGOS SLEEP
     *
     * @param millis Várakozási idő, ezredmásodpercben.
     */
    private void sleep(long millis) {

        // TESZT
        Assert.assertTrue(millis > 0);

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            e.printStackTrace();
            Log.d(TAG, "Error! " + e);
        }
    }


    /**
     * HELYKISZOLGÁLÓ KIVÁLASZTÁSA
     * A legjobb pontossággal rendelkezőt keresi meg. Felírja a legjobb pontosságot is, osztály változóba.
     *
     * @return A legpontosabb Location objektummal tér vissza
     */
    private Location getLastKnownLocation() {

        // Teszt
        Assert.assertNotNull(locationManager);

        // Megvalósítás
        List<String> providers = locationManager.getProviders(true);
        Location bestLocation = null;
        for (String provider : providers) {
            Location l = locationManager.getLastKnownLocation(provider);

            // Ha nincs, kövi
            if (null == l) continue;

            // Vagy az elsőt, vagy a jobb pontossággal rendelkezőt felírjuk
            if (bestLocation == null || l.getAccuracy() < bestLocation.getAccuracy()) {

                // Ha jobb, felül írjuk
                bestLocation = l;

                // NOTE: Nem ítt írjuk fel
                //bestAccurate = bestLocation.getAccuracy();
            }
        }

        if (bestLocation == null) {
            // TODO: Force gps update?
            Log.d(TAG, "Location detection error!");
            return null;
        }
        return bestLocation;
    }

    /**
     * CHECK INTERNET
     * Ellenőrzi az internet hozzáférést.
     *
     * @param context Kontextus
     * @return true/false - Van-e internet hozzáférés?
     */
    public boolean isOnline(Context context) {

        // Teszt
        Assert.assertNotNull(context);

        // Megvalósítás
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();

        return netInfo != null && netInfo.isConnectedOrConnecting();
    }


    // =======================================
    // ======== ToltoAllomas OBJEKTUM ========
    // =======================================

    private class ToltoAllomas implements Comparable<ToltoAllomas> {

        // Ár
        private double price;  // [kb 100.0 - 400.0]

        // Név
        // Pl: MOL vagy OMV, vagy Magán, stb
        private String name;  // Hossza: 3-6 karakter

        // Kút távolsága. (métert számolunk a km-ből)
        private int distance;

        // A kút pontos címe.
        // Város-kerület-utca-házszám.
        private String address; // Hossza: kb 10-30 karakter

        // Konstruktor
        public ToltoAllomas(double price, String name, double distance, String address) {

            // Teszt
            Assert.assertTrue(price > 0);
            Assert.assertNotNull(name);
            Assert.assertTrue(!name.isEmpty());
            Assert.assertTrue(distance > -1);
            Assert.assertNotNull(address);
            Assert.assertTrue(!address.isEmpty());

            this.price = price;
            this.name = name;
            // KM-ből méter.
            // Pl: 0.7 ---> 700
            this.distance = (int) (distance * 1000);
            this.address = address;
        }

        public double getPrice() {
            return price;
        }

        public String getName() {
            return name;
        }

        public int getDistance() {
            return distance;
        }

        public String getAddress() {
            return address;
        }

        @Override
        public int compareTo(ToltoAllomas compareToltoAllomasObj) {

            // Teszt
            Assert.assertNotNull(compareToltoAllomasObj);
            Assert.assertTrue(compareToltoAllomasObj.getPrice() > 0);

            // Összehasonlítandó ára
            int comparePrice = (int) ((ToltoAllomas) compareToltoAllomasObj).getPrice();

            // Teszt
            Assert.assertTrue(comparePrice > 0);

            // asc - növekvő
            return ((int) this.price) - comparePrice;
            // NOTE: desc(csökkenő)-hez fordítsd meg a két paramétert!
        }

        @Override
        public String toString() {
            return "ToltoAllomas{" +
                    "price=" + price +
                    ", name='" + name + '\'' +
                    ", distance=" + distance +
                    ", address='" + address + '\'' +
                    '}';
        }
    }
} // Class vége :)