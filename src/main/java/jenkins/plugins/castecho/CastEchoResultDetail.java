package jenkins.plugins.castecho;

import hudson.FilePath;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class CastEchoResultDetail implements Serializable {
    private  static  final  long serialVersionUID = 1350092991346723535L;
    
    static class Detail implements Serializable  {
        String name;
        long count;
        Detail(String name, long count)  { this.name = name;  this.count = count; }
        }
    
    static class ViolationType implements Serializable  {
        String name;
        long count;
        List<Detail> details;
        ViolationType(String name, long count)  { this.name = name;  this.count = count;  details = new ArrayList<>(); }
        }

    LinkedList<ViolationType> violationTypes;
    
    protected CastEchoResultDetail()  { violationTypes = new LinkedList<>(); }
    
    static final class Collect implements FilePath.FileCallable<CastEchoResultDetail>  {
        @Override
        public CastEchoResultDetail invoke(File f, VirtualChannel channel)  {
            JSONParser jsonParser = new JSONParser();
            CastEchoResultDetail result = null;
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))  {
                JSONArray jsonMain = (JSONArray)jsonParser.parse(reader);
                result = new CastEchoResultDetail();
                for (Object _jsonViolation : jsonMain)  {
                    JSONObject jsonViolation = (JSONObject)_jsonViolation;
                    ViolationType violation = new ViolationType((String)jsonViolation.get("Tag Name"), (long)jsonViolation.get("Number of violation"));
                    for (Object _jsonDetail : (JSONArray)jsonViolation.get("Details"))  {
                        JSONObject jsonDetail = (JSONObject)_jsonDetail;
                        Detail detail = new Detail((String)jsonDetail.get("Violation Name"), (long)jsonDetail.get("Number of violation"));
                        violation.details.add(detail);
                        }
                    result.violationTypes.add(violation);
                    }
                }
            catch (IOException | ParseException e)  { }
            return result;
            }

        @Override
        public void checkRoles(org.jenkinsci.remoting.RoleChecker checker) throws SecurityException  { }
        }
    
    }
